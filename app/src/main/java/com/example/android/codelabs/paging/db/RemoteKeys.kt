package com.example.android.codelabs.paging.db

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * For the purposes of the Github API, the page key that we use to request pages of repos is just a
 * page index that is incremented when getting the next page. This means that given a Repo object,
 * the next batch of Repo objects can be requested based on page index + 1. The previous batch of
 * Repo objects can be requested based on page index - 1. All Repo objects received on a certain
 * page response will have the same next and previous keys.

When we get the last item loaded from the PagingState, there's no way to know the index of the page
it belonged to. To solve this problem, we can add another table that stores the next and previous
page keys for each Repo; we can call it remote_keys. While this can be done in the Repo table,
creating a new table for the next and previous remote keys associated with a Repo allows us to have
a better separation of concerns.
 */
@Entity(tableName = "remote_keys")
data class RemoteKeys(
        @PrimaryKey
        val repoId: Long,
        val prevKey: Int?,
        val nextKey: Int?
)