package com.example.android.codelabs.paging.data

import androidx.paging.ExperimentalPagingApi
import androidx.paging.LoadType
import androidx.paging.PagingState
import androidx.paging.RemoteMediator
import androidx.room.withTransaction
import com.example.android.codelabs.paging.api.GithubService
import com.example.android.codelabs.paging.api.IN_QUALIFIER
import com.example.android.codelabs.paging.db.RemoteKeys
import com.example.android.codelabs.paging.db.RepoDatabase
import com.example.android.codelabs.paging.model.Repo
import retrofit2.HttpException
import java.io.IOException
import java.io.InvalidObjectException

/**
 * The Paging library uses the database as a source of truth for the data that needs to be displayed
 * in the UI. Whenever we don't have any more data in the database, we need to request more from the
 * network. To help with this, Paging 3.0 defines the RemoteMediator abstract class, with one method
 * that needs to be implemented: load(). This method will be called whenever we need to load more
 * data from the network. This class returns a MediatorResult object, that can either be:
 *  - Error : if we got an error while requesting data from the network.
 *  - Success : If we successfully got data from the network. Here, we also need to pass in a signal
 *  that tells whether more data can be loaded or not. For example, if the network response was
 *  successful but we got an empty list of repositories, it means that there is no more data to be
 *  loaded.
 *
 *  To be able to build the network request, the load method has 2 parameters that should give us
 *  all the information we need:
 *  1. PagingState - this gives us information about the pages that were loaded before, the most
 *  recently accessed index in the list, and the PagingConfig we defined when initializing the
 *  paging stream.
 *
 *  2. LoadType - this tells us whether we need to load data at the end (LoadType.APPEND) or at the
 *  beginning of the data (LoadType.PREPEND) that we previously loaded, or if this the first time
 *  we're loading data (LoadType.REFRESH).
 *
 *  For example, if the load type is LoadType.APPEND then we retrieve the last item that was loaded
 *  from the PagingState. Based on that we should be able to find out how to load the next batch of Repo objects, by computing the next page to be loaded.
 */
private const val GITHUB_STARTING_PAGE_INDEX = 1

@OptIn(ExperimentalPagingApi::class)
class GithubRemoteMediator(
        private val query: String,
        private val service: GithubService,
        private val repoDatabase: RepoDatabase
) : RemoteMediator<Int, Repo>() {

    /**
     * How we can implement the GithubRemoteMediator.load() method:
     *
     * 1. Find out what page we need to load from the network, based on the LoadType.
     * 2. Trigger the network request.
     * 3. Once the network request completes, if the received list of repositories is not empty, then do the following:
     *      1. We compute the RemoteKeys for every Repo.
     *      2. If this a new query (loadType = REFRESH) then we clear the database.
     *      3. Save the RemoteKeys and Repos in the database.
     *      4. Return MediatorResult.Success(endOfPaginationReached = false).
     *      5. If the list of repos was empty then we
     *      return MediatorResult.Success(endOfPaginationReached = true).
     * If we get an error requesting data we return MediatorResult.Error.
     */
    override suspend fun load(loadType: LoadType, state: PagingState<Int, Repo>): MediatorResult {
        val page = when (loadType) {
            /**
             * LoadType.REFRESH gets called when it's the first time we're loading data, or when
             * PagingDataAdapter.refresh() is called; so now the point of reference for loading our
             * data is the state.anchorPosition. If this is the first load, then the anchorPosition
             * is null. When PagingDataAdapter.refresh() is called, the anchorPosition is the first
             * visible position in the displayed list, so we will need to load the page that
             * contains that specific item.
             *
             *      1. Based on the anchorPosition from the state, we can get the closest Repo item
             *      to that     position by calling state.closestItemToPosition().
             *
             *      2. Based on the Repo item, we can get the RemoteKeys from the database.
             */
            LoadType.REFRESH -> {
                val remoteKeys = getRemoteKeyClosestToCurrentPosition(state)
                remoteKeys?.nextKey?.minus(1) ?: GITHUB_STARTING_PAGE_INDEX
            }
            /**
             *  When we need to load data at the beginning of the currently loaded data set, the
             *  load parameter is LoadType.PREPEND
              */
            LoadType.PREPEND -> {
                val remoteKeys = getRemoteKeyForFirstItem(state)
                if (remoteKeys == null) {
                    // The LoadType is PREPEND so some data was loaded before,
                    // so we should have been able to get remote keys
                    // If the remoteKeys are null, then we're an invalid state and we have a bug
                    throw InvalidObjectException("Remote key and the prevKey should not be null")
                }
                // If the previous key is null, then we can't request more data
                val prevKey = remoteKeys.prevKey
                if (prevKey == null) {
                    return MediatorResult.Success(endOfPaginationReached = true)
                }
                remoteKeys.prevKey
            }
            /**
             *  When we need to load data at the end of the currently loaded data set, the load
             *  parameter is LoadType.APPEND
              */
            LoadType.APPEND -> {
                val remoteKeys = getRemoteKeyForLastItem(state)
                if (remoteKeys?.nextKey == null) {
                    throw InvalidObjectException("Remote key should not be null for $loadType")
                }
                remoteKeys.nextKey
            }
        }
        val apiQuery = query + IN_QUALIFIER

        try {
            val apiResponse = service.searchRepos(apiQuery, page, state.config.pageSize)

            val repos = apiResponse.items
            val endOfPaginationReached = repos.isEmpty()
            repoDatabase.withTransaction {
                // clear all tables in the database
                if (loadType == LoadType.REFRESH) {
                    repoDatabase.remoteKeysDao().clearRemoteKeys()
                    repoDatabase.reposDao().clearRepos()
                }
                val prevKey = if (page == GITHUB_STARTING_PAGE_INDEX) null else page - 1
                val nextKey = if (endOfPaginationReached) null else page + 1
                val keys = repos.map {
                    RemoteKeys(repoId = it.id, prevKey = prevKey, nextKey = nextKey)
                }
                repoDatabase.remoteKeysDao().insertAll(keys)
                repoDatabase.reposDao().insertAll(repos)
            }
            return MediatorResult.Success(endOfPaginationReached = endOfPaginationReached)
        } catch (exception: IOException) {
            return MediatorResult.Error(exception)
        } catch (exception: HttpException) {
            return MediatorResult.Error(exception)
        }
    }

    private suspend fun getRemoteKeyForLastItem(state: PagingState<Int, Repo>): RemoteKeys? {
        // Get the last page that was retrieved, that contained items.
        // From that last page, get the last item
        return state.pages.lastOrNull { it.data.isNotEmpty() }?.data?.lastOrNull()?.let { repo ->
            // Get the remote keys of the last item retrieved
            repoDatabase.remoteKeysDao().remoteKeysRepoId(repo.id)
        }
    }

    private suspend fun getRemoteKeyForFirstItem(state: PagingState<Int, Repo>): RemoteKeys? {
        // Get the first page that was retrieved, that contained items.
        // From that first page, get the first item
        return state.pages.firstOrNull { it.data.isNotEmpty() }?.data?.firstOrNull()
                ?.let { repo ->
                    // Get the remote keys of the first items retrieved
                    repoDatabase.remoteKeysDao().remoteKeysRepoId(repo.id)
                }
    }

    private suspend fun getRemoteKeyClosestToCurrentPosition(
            state: PagingState<Int, Repo>
    ): RemoteKeys? {
        // The paging library is trying to load data after the anchor position
        // Get the item closest to the anchor position
        return state.anchorPosition?.let { position ->
            state.closestItemToPosition(position)?.id?.let { repoId ->
                repoDatabase.remoteKeysDao().remoteKeysRepoId(repoId)
            }
        }
    }
}