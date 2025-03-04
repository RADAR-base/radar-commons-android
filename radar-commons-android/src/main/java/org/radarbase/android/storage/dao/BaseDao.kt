package org.radarbase.android.storage.dao

import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Update

/**
 * A generic base Data Access Object (DAO) interface providing standard CRUD operations.
 *
 * This interface defines common methods for adding, updating, and deleting entities of type [T].
 * Implementing DAOs can extend this interface to inherit these operations.
 *
 * @param T the type of entity for which the operations are provided.
 */
@Suppress("unused")
interface BaseDao<T> {

    /**
     * Inserts the given [data] into the database.
     *
     * If a conflict occurs (e.g. a record with the same primary key already exists),
     * the existing record will be replaced.
     *
     * @param data the entity to be inserted.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun add(data: T)

    /**
     * Inserts the given [rows] into the database.
     *
     * If a conflict occurs, the existing records will be replaced.
     *
     * @param rows a variable number of entities to be inserted.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun addAll(rows: List<T>)

    /**
     * Updates the given [data] in the database.
     *
     * @param data the entity with updated values.
     */
    @Update
    fun update(data: T)

    /**
     * Deletes the specified [row] from the database.
     *
     * @param row the entity to be deleted.
     */
    @Delete
    fun delete(row: T)

    /**
     * Deletes the specified [rows] from the database.
     *
     * @param rows a variable number of entities to be deleted.
     */
    @Delete
    fun deleteAll(vararg rows: T)
}
