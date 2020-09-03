package com.example.android.codelabs.paging.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.example.android.codelabs.paging.db.RemoteKeys

@Dao
interface RemoteKeysDao {

    // Insert a list of RemoteKeys, as whenever we get Repos from the network we will generate the remote keys for them.
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(remoteKey: List<RemoteKeys>)

    // Get a RemoteKey based on a Repo id.
    @Query("SELECT * FROM remote_keys WHERE repoId = :repoId")
    suspend fun remoteKeysRepoId(repoId: Long): RemoteKeys?

    // Clear the RemoteKeys, which we will use whenever we have a new query.
    @Query("DELETE FROM remote_keys")
    suspend fun clearRemoteKeys()
}