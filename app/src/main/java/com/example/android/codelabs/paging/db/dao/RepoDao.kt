package com.example.android.codelabs.paging.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.android.codelabs.paging.model.Repo

@Dao
interface RepoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(repos: List<Repo>)

    /**
     * Query for repos that contain the query string in the name or in the description and sort
     * those results in descending order by the number of stars and then alphabetically by name.
     *
     * Instead of returning a List<Repo>, return PagingSource<Int, Repo>. That way, the repos table
     * becomes the source of data for Paging.
     */
    @Query("SELECT * FROM repos WHERE " +
            "name LIKE :queryString OR description LIKE :queryString " +
            "ORDER BY stars DESC, name ASC")
    fun reposByName(queryString: String): PagingSource<Int, Repo>

    @Query("DELETE FROM repos")
    suspend fun clearRepos()
}