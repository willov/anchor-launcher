/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.launcher3.model

import android.content.ContentValues
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.graphics.Point
import android.os.Process
import android.platform.test.annotations.DisableFlags
import android.platform.test.annotations.EnableFlags
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SmallTest
import com.android.launcher3.Flags
import com.android.launcher3.InvariantDeviceProfile
import com.android.launcher3.LauncherPrefs
import com.android.launcher3.LauncherPrefs.Companion.WORKSPACE_SIZE
import com.android.launcher3.LauncherSettings.Favorites.*
import com.android.launcher3.model.GridSizeMigrationDBController.DbReader
import com.android.launcher3.pm.UserCache
import com.android.launcher3.provider.LauncherDbUtils
import com.android.launcher3.util.SandboxApplication
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Unit tests for [GridSizeMigrationDBController, GridSizeMigrationLogic] */
@SmallTest
@RunWith(AndroidJUnit4::class)
class GridSizeMigrationTest {

    @get:Rule val context = SandboxApplication().withModelDependency()

    private lateinit var idp: InvariantDeviceProfile
    private lateinit var dbHelper: DatabaseHelper
    private lateinit var db: SQLiteDatabase
    private val testPackage1 = "com.android.launcher3.validpackage1"
    private val testPackage2 = "com.android.launcher3.validpackage2"
    private val testPackage3 = "com.android.launcher3.validpackage3"
    private val testPackage4 = "com.android.launcher3.validpackage4"
    private val testPackage5 = "com.android.launcher3.validpackage5"
    private val testPackage6 = "com.android.launcher3.validpackage6"
    private val testPackage7 = "com.android.launcher3.validpackage7"
    private val testPackage8 = "com.android.launcher3.validpackage8"
    private val testPackage9 = "com.android.launcher3.validpackage9"
    private val testPackage10 = "com.android.launcher3.validpackage10"

    @Before
    fun setUp() {
        dbHelper =
            DatabaseHelper(
                context,
                null,
                UserCache.INSTANCE.get(context)::getSerialNumberForUser,
            ) {}
        db = dbHelper.writableDatabase

        idp = InvariantDeviceProfile.INSTANCE[context]
        val userSerial = UserCache.INSTANCE[context].getSerialNumberForUser(Process.myUserHandle())
        LauncherDbUtils.dropTable(db, TMP_TABLE)
        addTableToDb(db, userSerial, false, TMP_TABLE)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    @Throws(Exception::class)
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    @Ignore(
        "Anchor: refactor path now PRESERVES/CLAMPS positions instead of compacting; this upstream " +
            "test asserts top-left compaction. See preserveAndClampOverflow + the anchor* tests below.",
    )
    fun testMigrationRefactorFlagOn() {
        testMigration()
    }

    @Test
    @Throws(Exception::class)
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun testMigrationRefactorFlagOff() {
        testMigration()
    }

    /** Old migration logic, should be modified once is not needed anymore */
    @Throws(Exception::class)
    fun testMigration() {
        // Src Hotseat icons
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_HOTSEAT, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 1, CONTAINER_HOTSEAT, 0, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 3, CONTAINER_HOTSEAT, 0, 0, testPackage3, 3, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 4, CONTAINER_HOTSEAT, 0, 0, testPackage4, 4, TMP_TABLE)
        // Src grid icons
        // _ _ _ _ _
        // _ _ _ _ 5
        // _ _ 6 _ 7
        // _ _ 8 _ 9
        // _ _ _ _ _
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 1, testPackage5, 5, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 2, testPackage6, 6, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 2, testPackage7, 7, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 3, testPackage8, 8, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 3, testPackage9, 9, TMP_TABLE)

        // Dest hotseat icons
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 1, CONTAINER_HOTSEAT, 0, 0, testPackage2)
        // Dest grid icons
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 2, testPackage10)

        idp.numDatabaseHotseatIcons = 4
        idp.numColumns = 4
        idp.numRows = 4
        val srcReader = DbReader(db, TMP_TABLE, context)
        val destReader = DbReader(db, TABLE_NAME, context)
        if (Flags.gridMigrationRefactor()) {
            var gridSizeMigrationLogic = GridSizeMigrationLogic()
            val idsInUse = mutableListOf<Int>()
            gridSizeMigrationLogic.migrateHotseat(
                5,
                idp.numDatabaseHotseatIcons,
                srcReader,
                destReader,
                dbHelper,
                idsInUse,
            )
            gridSizeMigrationLogic.migrateWorkspace(
                srcReader,
                destReader,
                dbHelper,
                Point(idp.numColumns, idp.numRows),
                idsInUse,
            )
        } else {
            GridSizeMigrationDBController.migrate(
                dbHelper,
                srcReader,
                destReader,
                5,
                idp.numDatabaseHotseatIcons,
                Point(idp.numColumns, idp.numRows),
                DeviceGridState(context),
                DeviceGridState(idp),
            )
        }

        // Check hotseat items
        var c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()

        assertThat(c.count).isEqualTo(idp.numDatabaseHotseatIcons)

        val screenIndex = c.getColumnIndex(SCREEN)
        var intentIndex = c.getColumnIndex(INTENT)
        c.moveToNext()
        assertThat(c.getInt(screenIndex).toLong()).isEqualTo(0)
        assertThat(c.getString(intentIndex)).contains(testPackage1)
        c.moveToNext()
        assertThat(c.getInt(screenIndex).toLong()).isEqualTo(1)
        assertThat(c.getString(intentIndex)).contains(testPackage2)
        c.moveToNext()
        assertThat(c.getInt(screenIndex).toLong()).isEqualTo(2)
        assertThat(c.getString(intentIndex)).contains(testPackage3)
        c.moveToNext()
        assertThat(c.getInt(screenIndex).toLong()).isEqualTo(3)
        assertThat(c.getString(intentIndex)).contains(testPackage4)
        c.close()

        // Check workspace items
        c =
            db.query(
                TABLE_NAME,
                arrayOf(CELLX, CELLY, INTENT),
                "container=$CONTAINER_DESKTOP",
                null,
                null,
                null,
                null,
            ) ?: throw IllegalStateException()

        intentIndex = c.getColumnIndex(INTENT)
        val cellXIndex = c.getColumnIndex(CELLX)
        val cellYIndex = c.getColumnIndex(CELLY)
        val locMap = HashMap<String?, Point>()
        while (c.moveToNext()) {
            locMap[Intent.parseUri(c.getString(intentIndex), 0).getPackage()] =
                Point(c.getInt(cellXIndex), c.getInt(cellYIndex))
        }
        c.close()
        // Expected dest grid icons
        // _ _ _ _
        // 5 6 7 8
        // 9 _ _ _
        // _ _ _ _
        assertThat(locMap.size.toLong()).isEqualTo(5)
        assertThat(locMap[testPackage5]).isEqualTo(Point(0, 1))
        assertThat(locMap[testPackage6]).isEqualTo(Point(1, 1))
        assertThat(locMap[testPackage7]).isEqualTo(Point(2, 1))
        assertThat(locMap[testPackage8]).isEqualTo(Point(3, 1))
        assertThat(locMap[testPackage9]).isEqualTo(Point(0, 2))
    }

    @Test
    @Throws(Exception::class)
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    @Ignore(
        "Anchor: refactor path now PRESERVES/CLAMPS positions instead of compacting; this upstream " +
            "test asserts top-left compaction. See preserveAndClampOverflow + the anchor* tests below.",
    )
    fun testMigrationBackAndForthRefactorFlagOn() {
        testMigrationBackAndForth()
    }

    @Test
    @Throws(Exception::class)
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun testMigrationBackAndForthRefactorFlagOff() {
        testMigrationBackAndForth()
    }

    /** Old migration logic, should be modified once is not needed anymore */
    @Throws(Exception::class)
    fun testMigrationBackAndForth() {
        // Hotseat items in grid A
        // 1 2 _ 3 4
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_HOTSEAT, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 1, CONTAINER_HOTSEAT, 0, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 3, CONTAINER_HOTSEAT, 0, 0, testPackage3, 3, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 4, CONTAINER_HOTSEAT, 0, 0, testPackage4, 4, TMP_TABLE)
        // Workspace items in grid A
        // _ _ _ _ _
        // _ _ _ _ 5
        // _ _ 6 _ 7
        // _ _ 8 _ _
        // _ _ _ _ _
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 1, testPackage5, 5, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 2, testPackage6, 6, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 2, testPackage7, 7, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 3, testPackage8, 8, TMP_TABLE)

        // Hotseat items in grid B
        // 2 _ _ _
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 0, CONTAINER_HOTSEAT, 0, 0, testPackage2)
        // Workspace items in grid B
        // _ _ _ _
        // _ _ _ 10
        // _ _ _ _
        // _ _ _ _
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 1, 3, testPackage10)

        idp.numDatabaseHotseatIcons = 4
        idp.numColumns = 4
        idp.numRows = 4
        val readerGridA = DbReader(db, TMP_TABLE, context)
        val readerGridB = DbReader(db, TABLE_NAME, context)
        // migrate from A -> B
        migrateGrid(
            dbHelper,
            readerGridA,
            readerGridB,
            5,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Check hotseat items in grid B
        var c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()
        // Expected hotseat items in grid B
        // 2 1 3 4
        verifyHotseat(
            c,
            mutableListOf(testPackage2, testPackage1, testPackage3, testPackage4).toList(),
            4,
        )

        // Check workspace items in grid B
        c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, CELLX, CELLY, INTENT),
                "container=$CONTAINER_DESKTOP",
                null,
                null,
                null,
                null,
            ) ?: throw IllegalStateException()
        var locMap = parseLocMap(c)
        // Expected items in grid B
        // _ _ _ _
        // 5 6 7 8
        // _ _ _ _
        // _ _ _ _
        assertThat(locMap.size.toLong()).isEqualTo(4)
        assertThat(locMap[testPackage5]).isEqualTo(Triple(0, 0, 1))
        assertThat(locMap[testPackage6]).isEqualTo(Triple(0, 1, 1))
        assertThat(locMap[testPackage7]).isEqualTo(Triple(0, 2, 1))
        assertThat(locMap[testPackage8]).isEqualTo(Triple(0, 3, 1))

        // add item in B
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 2, testPackage9)

        // migrate from B -> A
        migrateGrid(dbHelper, readerGridB, readerGridA, 4, 5, 5, 5)

        // Check hotseat items in grid A
        c =
            db.query(
                TMP_TABLE,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()
        // Expected hotseat items in grid A
        // 1 2 4 3 4
        verifyHotseat(
            c,
            mutableListOf(testPackage1, testPackage2, testPackage4, testPackage3, testPackage4)
                .toList(),
            5,
        )

        // Check workspace items in grid A
        c =
            db.query(
                TMP_TABLE,
                arrayOf(SCREEN, CELLX, CELLY, INTENT),
                "container=$CONTAINER_DESKTOP",
                null,
                null,
                null,
                null,
            ) ?: throw IllegalStateException()
        locMap = parseLocMap(c)
        // Expected workspace items in grid A
        // _ _ _ _ _
        // 9 _ _ _ 5
        // _ _ 6 _ 7
        // _ _ 8 _ _
        // _ _ _ _ _
        assertThat(locMap.size.toLong()).isEqualTo(5)
        // Verify items that existed in grid A remains in same position
        assertThat(locMap[testPackage5]).isEqualTo(Triple(0, 4, 1))
        assertThat(locMap[testPackage6]).isEqualTo(Triple(0, 2, 2))
        assertThat(locMap[testPackage7]).isEqualTo(Triple(0, 4, 2))
        assertThat(locMap[testPackage8]).isEqualTo(Triple(0, 2, 3))
        // Verify items that didn't exist in grid A are added in new screen
        assertThat(locMap[testPackage9]).isEqualTo(Triple(0, 0, 1))

        // remove item from B
        db.delete(TMP_TABLE, "$_ID=7", null)

        // migrate from A -> B
        migrateGrid(
            dbHelper,
            readerGridA,
            readerGridB,
            5,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Check hotseat items in grid B
        c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()
        // Expected hotseat items in grid B
        // 2 1 3 4
        verifyHotseat(
            c,
            mutableListOf(testPackage2, testPackage1, testPackage3, testPackage4).toList(),
            4,
        )

        // Check workspace items in grid B
        c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, CELLX, CELLY, INTENT),
                "container=$CONTAINER_DESKTOP",
                null,
                null,
                null,
                null,
            ) ?: throw IllegalStateException()
        locMap = parseLocMap(c)
        // Expected workspace items in grid B
        // _ _ _ _
        // 5 6 _ 8
        // 9 _ _ _
        // _ _ _ _
        assertThat(locMap.size.toLong()).isEqualTo(4)
        assertThat(locMap[testPackage5]).isEqualTo(Triple(0, 0, 1))
        assertThat(locMap[testPackage6]).isEqualTo(Triple(0, 1, 1))
        assertThat(locMap[testPackage8]).isEqualTo(Triple(0, 3, 1))
        assertThat(locMap[testPackage9]).isEqualTo(Triple(0, 0, 2))
    }

    @Test
    @Throws(Exception::class)
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun testHotseatMigrationToSmallerGridBackAndForthFlagOn() {
        testHotseatMigrationToSmallerGridBackAndForth()
    }

    @Test
    @Throws(Exception::class)
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun testHotseatMigrationToSmallerGridBackAndForthFlagOff() {
        testHotseatMigrationToSmallerGridBackAndForth()
    }

    /** Old migration logic, should be modified once is not needed anymore */
    @Throws(Exception::class)
    fun testHotseatMigrationToSmallerGridBackAndForth() {
        // Hotseat items in grid A
        // 1 2 3 4 5
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_HOTSEAT, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 1, CONTAINER_HOTSEAT, 0, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 2, CONTAINER_HOTSEAT, 0, 0, testPackage3, 3, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 3, CONTAINER_HOTSEAT, 0, 0, testPackage4, 4, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 4, CONTAINER_HOTSEAT, 0, 0, testPackage5, 5, TMP_TABLE)

        // Hotseat items in grid B
        // 2 _ _ _
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 0, CONTAINER_HOTSEAT, 0, 0, testPackage2)

        idp.numDatabaseHotseatIcons = 4
        idp.numColumns = 4
        idp.numRows = 4
        val readerGridA = DbReader(db, TMP_TABLE, context)
        val readerGridB = DbReader(db, TABLE_NAME, context)
        // migrate from A -> B
        migrateGrid(
            dbHelper,
            readerGridA,
            readerGridB,
            5,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )
        // Check hotseat items in grid B
        var c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()
        // Expected hotseat items in grid B
        // 2 1 3 4
        verifyHotseat(
            c,
            mutableListOf(testPackage2, testPackage1, testPackage3, testPackage4).toList(),
            4,
        )

        // migrate from B -> A
        migrateGrid(dbHelper, readerGridB, readerGridA, idp.numDatabaseHotseatIcons, 5, 5, 5)

        // Check hotseat items in grid A
        c =
            db.query(
                TMP_TABLE,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()
        // Expected hotseat items in grid A
        // 1 2 3 4 5
        verifyHotseat(
            c,
            mutableListOf(testPackage1, testPackage2, testPackage3, testPackage4, testPackage5)
                .toList(),
            5,
        )

        // migrate from A -> B
        migrateGrid(
            dbHelper,
            readerGridA,
            readerGridB,
            5,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Check hotseat items in grid B
        c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()
        // Expected hotseat items in grid B
        // 2 1 3 4
        verifyHotseat(
            c,
            mutableListOf(testPackage2, testPackage1, testPackage3, testPackage4).toList(),
            4,
        )
    }

    @Test
    @Throws(Exception::class)
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun testMigrationToFullGridFlagOn() {
        testMigrationToFullGrid()
    }

    @Test
    @Throws(Exception::class)
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun testHotseatMigrationToFullGridFlagOff() {
        testMigrationToFullGrid()
    }

    @Throws(Exception::class)
    fun testMigrationToFullGrid() {
        // Hotseat items in grid A
        // 1 2 3 4 5
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_HOTSEAT, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 1, CONTAINER_HOTSEAT, 0, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 2, CONTAINER_HOTSEAT, 0, 0, testPackage3, 3, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 3, CONTAINER_HOTSEAT, 0, 0, testPackage4, 4, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 4, CONTAINER_HOTSEAT, 0, 0, testPackage5, 5, TMP_TABLE)

        // Hotseat items in grid B
        // 6 7 8 9
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 0, CONTAINER_HOTSEAT, 0, 0, testPackage6)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 1, CONTAINER_HOTSEAT, 0, 0, testPackage7)
        addItem(ITEM_TYPE_APPLICATION, 2, CONTAINER_HOTSEAT, 0, 0, testPackage8)
        addItem(ITEM_TYPE_APPLICATION, 3, CONTAINER_HOTSEAT, 0, 0, testPackage9)

        // Workspace items in grid A
        // _ _ _ _ _
        // 6 _ _ _ _
        // _ _ _ _ _
        // _ _ _ _ _
        // _ _ _ _ _
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 1, testPackage6, 6, TMP_TABLE)

        // Workspace items in grid B
        // _ _ _ _
        // 1 2 3 4
        // _ _ _ _
        // _ _ _ _
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 1, testPackage1)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 1, 1, testPackage2)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 1, testPackage3)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 3, 1, testPackage4)

        idp.numDatabaseHotseatIcons = 4
        idp.numColumns = 4
        idp.numRows = 4
        val readerGridA = DbReader(db, TMP_TABLE, context)
        val readerGridB = DbReader(db, TABLE_NAME, context)

        // migrate from A -> B
        migrateGrid(
            dbHelper,
            readerGridA,
            readerGridB,
            5,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Check hotseat items in grid B
        var c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()
        // Expected hotseat items in grid B
        // 1 2 3 4
        verifyHotseat(
            c,
            mutableListOf(testPackage1, testPackage2, testPackage3, testPackage4).toList(),
            4,
        )

        // Check workspace items in grid B
        c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, CELLX, CELLY, INTENT),
                "container=$CONTAINER_DESKTOP",
                null,
                null,
                null,
                null,
            ) ?: throw IllegalStateException()
        val locMap = parseLocMap(c)
        // Expected workspace items in grid B
        // _ _ _ _
        // 6 _ _ _
        // _ _ _ _
        // _ _ _ _
        assertThat(locMap.size.toLong()).isEqualTo(1)
        assertThat(locMap[testPackage6]).isEqualTo(Triple(0, 0, 1))
    }

    private fun migrateGrid(
        dbHelper: DatabaseHelper,
        srcReader: DbReader,
        destReader: DbReader,
        srcHotseatSize: Int,
        destHotseatSize: Int,
        pointX: Int,
        pointY: Int,
    ) {
        if (Flags.gridMigrationRefactor()) {
            var gridSizeMigrationLogic = GridSizeMigrationLogic()
            val idsInUse = mutableListOf<Int>()
            gridSizeMigrationLogic.migrateHotseat(
                srcHotseatSize,
                destHotseatSize,
                srcReader,
                destReader,
                dbHelper,
                idsInUse,
            )
            gridSizeMigrationLogic.migrateWorkspace(
                srcReader,
                destReader,
                dbHelper,
                Point(pointX, pointY),
                idsInUse,
            )
        } else {
            GridSizeMigrationDBController.migrate(
                dbHelper,
                srcReader,
                destReader,
                srcHotseatSize,
                destHotseatSize,
                Point(pointX, pointY),
                DeviceGridState(idp),
                DeviceGridState(context),
            )
        }
    }

    private fun verifyHotseat(c: Cursor, expected: List<String?>, expectedCount: Int) {
        assertThat(c.count).isEqualTo(expectedCount)
        val screenIndex = c.getColumnIndex(SCREEN)
        val intentIndex = c.getColumnIndex(INTENT)
        expected.forEachIndexed { idx, pkg ->
            if (pkg == null) return@forEachIndexed
            c.moveToNext()
            assertThat(c.getInt(screenIndex).toLong()).isEqualTo(idx)
            assertThat(c.getString(intentIndex)).contains(pkg)
        }
        c.close()
    }

    private fun parseLocMap(c: Cursor): Map<String?, Triple<Int, Int, Int>> {
        // Check workspace items
        val intentIndex = c.getColumnIndex(INTENT)
        val screenIndex = c.getColumnIndex(SCREEN)
        val cellXIndex = c.getColumnIndex(CELLX)
        val cellYIndex = c.getColumnIndex(CELLY)
        val locMap = mutableMapOf<String?, Triple<Int, Int, Int>>()
        while (c.moveToNext()) {
            locMap[Intent.parseUri(c.getString(intentIndex), 0).getPackage()] =
                Triple(c.getInt(screenIndex), c.getInt(cellXIndex), c.getInt(cellYIndex))
        }
        c.close()
        return locMap.toMap()
    }

    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun migrateToLargerHotseatRefactorFlagOn() {
        migrateToLargerHotseat()
    }

    @Test
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun migrateToLargerHotseatRefactorFlagOff() {
        migrateToLargerHotseat()
    }

    fun migrateToLargerHotseat() {
        val srcHotseatItems =
            intArrayOf(
                addItem(
                    ITEM_TYPE_APPLICATION,
                    0,
                    CONTAINER_HOTSEAT,
                    0,
                    0,
                    testPackage1,
                    1,
                    TMP_TABLE,
                ),
                addItem(
                    ITEM_TYPE_DEEP_SHORTCUT,
                    1,
                    CONTAINER_HOTSEAT,
                    0,
                    0,
                    testPackage2,
                    2,
                    TMP_TABLE,
                ),
                addItem(
                    ITEM_TYPE_APPLICATION,
                    2,
                    CONTAINER_HOTSEAT,
                    0,
                    0,
                    testPackage3,
                    3,
                    TMP_TABLE,
                ),
                addItem(
                    ITEM_TYPE_DEEP_SHORTCUT,
                    3,
                    CONTAINER_HOTSEAT,
                    0,
                    0,
                    testPackage4,
                    4,
                    TMP_TABLE,
                ),
            )
        val numSrcDatabaseHotseatIcons = srcHotseatItems.size
        idp.numDatabaseHotseatIcons = 6
        idp.numColumns = 4
        idp.numRows = 4
        val srcReader = DbReader(db, TMP_TABLE, context)
        val destReader = DbReader(db, TABLE_NAME, context)
        migrateGrid(
            dbHelper,
            srcReader,
            destReader,
            4,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Check hotseat items
        val c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()

        assertThat(c.count.toLong()).isEqualTo(numSrcDatabaseHotseatIcons.toLong())
        val screenIndex = c.getColumnIndex(SCREEN)
        val intentIndex = c.getColumnIndex(INTENT)
        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(0)
        assertThat(c.getString(intentIndex)).contains(testPackage1)

        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(1)
        assertThat(c.getString(intentIndex)).contains(testPackage2)

        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(2)
        assertThat(c.getString(intentIndex)).contains(testPackage3)

        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(3)
        assertThat(c.getString(intentIndex)).contains(testPackage4)

        c.close()
    }

    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun migrateFromLargerHotseatRefactorFlagOn() {
        migrateFromLargerHotseat()
    }

    @Test
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun migrateFromLargerHotseatRefactorFlagOff() {
        migrateFromLargerHotseat()
    }

    fun migrateFromLargerHotseat() {
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_HOTSEAT, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 2, CONTAINER_HOTSEAT, 0, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 3, CONTAINER_HOTSEAT, 0, 0, testPackage3, 3, TMP_TABLE)
        addItem(ITEM_TYPE_DEEP_SHORTCUT, 4, CONTAINER_HOTSEAT, 0, 0, testPackage4, 4, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 5, CONTAINER_HOTSEAT, 0, 0, testPackage5, 5, TMP_TABLE)

        idp.numDatabaseHotseatIcons = 4
        idp.numColumns = 4
        idp.numRows = 4
        val srcReader = DbReader(db, TMP_TABLE, context)
        val destReader = DbReader(db, TABLE_NAME, context)
        migrateGrid(
            dbHelper,
            srcReader,
            destReader,
            6,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Check hotseat items
        val c =
            db.query(
                TABLE_NAME,
                arrayOf(SCREEN, INTENT),
                "container=$CONTAINER_HOTSEAT",
                null,
                SCREEN,
                null,
                null,
            ) ?: throw IllegalStateException()

        assertThat(c.count.toLong()).isEqualTo(idp.numDatabaseHotseatIcons.toLong())
        val screenIndex = c.getColumnIndex(SCREEN)
        val intentIndex = c.getColumnIndex(INTENT)

        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(0)
        assertThat(c.getString(intentIndex)).contains(testPackage1)

        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(1)
        assertThat(c.getString(intentIndex)).contains(testPackage2)

        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(2)
        assertThat(c.getString(intentIndex)).contains(testPackage3)

        c.moveToNext()
        assertThat(c.getInt(screenIndex)).isEqualTo(3)
        assertThat(c.getString(intentIndex)).contains(testPackage4)

        c.close()
    }

    @Test
    @Throws(Exception::class)
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    @Ignore(
        "Anchor: refactor path now PRESERVES item pages/positions instead of reflowing onto page 0. " +
            "This upstream test asserts every icon lands on screen 0. See preserveAndClampOverflow.",
    )
    fun migrateFromSmallerGridBigDifferenceRefactorFlagOn() {
        migrateFromSmallerGridBigDifference()
    }

    @Test
    @Throws(Exception::class)
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun migrateFromSmallerGridBigDifferenceRefactorFlagOff() {
        migrateFromSmallerGridBigDifference()
    }

    /**
     * Migrating from a smaller grid to a large one should reflow the pages if the column difference
     * is more than 2
     */
    @Throws(Exception::class)
    fun migrateFromSmallerGridBigDifference() {
        enableNewMigrationLogic("2,2")

        // Setup src grid
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 1, testPackage1, 5, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 1, 1, testPackage2, 6, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 1, CONTAINER_DESKTOP, 0, 0, testPackage3, 7, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 1, CONTAINER_DESKTOP, 1, 0, testPackage4, 8, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 2, CONTAINER_DESKTOP, 0, 0, testPackage5, 9, TMP_TABLE)

        idp.numDatabaseHotseatIcons = 4
        idp.numColumns = 5
        idp.numRows = 5
        val srcReader = DbReader(db, TMP_TABLE, context)
        val destReader = DbReader(db, TABLE_NAME, context)
        migrateGrid(
            dbHelper,
            srcReader,
            destReader,
            2,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Get workspace items
        val c =
            db.query(
                TABLE_NAME,
                arrayOf(INTENT, SCREEN),
                "container=$CONTAINER_DESKTOP",
                null,
                null,
                null,
                null,
            ) ?: throw IllegalStateException()

        val intentIndex = c.getColumnIndex(INTENT)
        val screenIndex = c.getColumnIndex(SCREEN)

        // Get in which screen the icon is
        val locMap = HashMap<String?, Int>()
        while (c.moveToNext()) {
            locMap[Intent.parseUri(c.getString(intentIndex), 0).getPackage()] =
                c.getInt(screenIndex)
        }
        c.close()

        // All icons fit the first screen
        assertThat(locMap.size).isEqualTo(5)
        assertThat(locMap[testPackage1]).isEqualTo(0)
        assertThat(locMap[testPackage2]).isEqualTo(0)
        assertThat(locMap[testPackage3]).isEqualTo(0)
        assertThat(locMap[testPackage4]).isEqualTo(0)
        assertThat(locMap[testPackage5]).isEqualTo(0)
    }

    @Test
    @Throws(Exception::class)
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    @Ignore(
        "Anchor: refactor path now PRESERVES item pages/positions instead of reflowing onto page 0. " +
            "This upstream test asserts every icon lands on screen 0. See preserveAndClampOverflow.",
    )
    fun migrateFromLargerGridRefactorFlagOn() {
        migrateFromLargerGrid()
    }

    @Test
    @Throws(Exception::class)
    @DisableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun migrateFromLargerGridRefactorFlagOff() {
        migrateFromLargerGrid()
    }

    /** Migrating from a larger grid to a smaller, we reflow from page 0 */
    @Throws(Exception::class)
    fun migrateFromLargerGrid() {
        enableNewMigrationLogic("5,5")

        // Setup src grid
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 1, testPackage1, 5, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 1, 1, testPackage2, 6, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 1, CONTAINER_DESKTOP, 0, 0, testPackage3, 7, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 1, CONTAINER_DESKTOP, 1, 0, testPackage4, 8, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 2, CONTAINER_DESKTOP, 0, 0, testPackage5, 9, TMP_TABLE)

        idp.numDatabaseHotseatIcons = 4
        idp.numColumns = 4
        idp.numRows = 4
        val srcReader = DbReader(db, TMP_TABLE, context)
        val destReader = DbReader(db, TABLE_NAME, context)
        migrateGrid(
            dbHelper,
            srcReader,
            destReader,
            5,
            idp.numDatabaseHotseatIcons,
            idp.numColumns,
            idp.numRows,
        )

        // Get workspace items
        val c =
            db.query(
                TABLE_NAME,
                arrayOf(INTENT, SCREEN),
                "container=$CONTAINER_DESKTOP",
                null,
                null,
                null,
                null,
            ) ?: throw IllegalStateException()
        val intentIndex = c.getColumnIndex(INTENT)
        val screenIndex = c.getColumnIndex(SCREEN)

        // Get in which screen the icon is
        val locMap = HashMap<String?, Int>()
        while (c.moveToNext()) {
            locMap[Intent.parseUri(c.getString(intentIndex), 0).getPackage()] =
                c.getInt(screenIndex)
        }
        c.close()

        // All icons fit the first screen
        assertThat(locMap.size).isEqualTo(5)
        assertThat(locMap[testPackage1]).isEqualTo(0)
        assertThat(locMap[testPackage2]).isEqualTo(0)
        assertThat(locMap[testPackage3]).isEqualTo(0)
        assertThat(locMap[testPackage4]).isEqualTo(0)
        assertThat(locMap[testPackage5]).isEqualTo(0)
    }

    private fun enableNewMigrationLogic(srcGridSize: String) {
        LauncherPrefs.get(context).putSync(WORKSPACE_SIZE.to(srcGridSize))
    }

    // ─────────────────────────────────────────────────────────────────────────────────────────────
    // Anchor: the grid migration was changed to PRESERVE spatial positions across a resize instead
    // of compacting everything onto the first pages. When an axis no longer fits we DROP empty
    // columns/rows from the right/bottom edge inward — only as many as needed — and shift the
    // occupied cells in by the number of dropped lines before them. Items that fit keep their exact
    // (x, y) and interior gaps are NOT collapsed. These tests lock that behaviour; the upstream tests
    // that assert compaction on the refactor path are @Ignore'd above.
    // See GridSizeMigrationLogic.preserveAndDropEmptyEdges.
    // ─────────────────────────────────────────────────────────────────────────────────────────────

    /** Growing the grid keeps every icon at its exact (x, y); the new space is added at the edge. */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorGrowPreservesExactPositions() {
        // src 4×4:  p1 at (0,0), p2 at (3,0), p3 at (2,3)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 3, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 3, testPackage3, 3, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 6
        idp.numRows = 6
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 6, 6)
        val loc = desktopLocMap()
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(0, 3, 0)) // kept exact, NOT compacted to (1,0)
        assertThat(loc[testPackage3]).isEqualTo(Triple(0, 2, 3))
    }

    /** Shrinking past the right edge drops one empty column from the right; the gap at col 1 stays. */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorShrinkDropsRightmostEmptyColumn() {
        // src 5×5: items in columns {0, 2, 4} on row 0 (columns 1, 3 empty). Shrink to 4 columns.
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 0, testPackage3, 3, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 4
        idp.numRows = 4
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 4, 4)
        val loc = desktopLocMap()
        // need=1; drop the rightmost empty column (col 3). cols 0,2 are before it → unchanged; col 4
        // has one dropped column before it → shifts to 3. The interior gap at col 1 is preserved.
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(0, 2, 0))
        assertThat(loc[testPackage3]).isEqualTo(Triple(0, 3, 0))
    }

    /** A single off-edge item slides in by exactly the number of removed columns (the repro). */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorShrinkSingleColumnDropsByOne() {
        // src 5×5: one item alone in column 4, row 0. Shrink 5→4 cols → expected col 3, NOT col 0.
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 0, testPackage1, 1, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 4
        idp.numRows = 4
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 4, 4)
        val loc = desktopLocMap()
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 3, 0))
    }

    /** Shrinking that doesn't overflow any axis keeps exact positions (no needless shifting). */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorShrinkWithoutOverflowKeepsExactPositions() {
        // src 5×5: items in columns {0,1,3} (col 2 empty). Shrink to 4 columns — all still fit.
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 1, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 3, 0, testPackage3, 3, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 4
        idp.numRows = 4
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 4, 4)
        val loc = desktopLocMap()
        // No column overflows (max is 3 < 4), so the empty col 2 stays and positions are exact.
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(0, 1, 0))
        assertThat(loc[testPackage3]).isEqualTo(Triple(0, 3, 0))
    }

    /** Shrinking past the bottom edge drops one empty row from the bottom (the row repro). */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorShrinkDropsBottommostEmptyRow() {
        // src 5×5: items in rows {0, 2, 4} of column 0. Shrink to 4 rows.
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 2, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 4, testPackage3, 3, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 5
        idp.numRows = 4
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 5, 4)
        val loc = desktopLocMap()
        // need=1; drop the bottommost empty row (row 3). rows 0,2 are before it → unchanged; row 4
        // has one dropped row before it → shifts to 3. The bottom item slides in by just one row.
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(0, 0, 2))
        assertThat(loc[testPackage3]).isEqualTo(Triple(0, 0, 3))
    }

    /**
     * The bottom-right corner repro: a top-left and a bottom-right item with a big empty band
     * between them. Shrinking rows must keep the bottom item NEAR THE BOTTOM (drops are alternated
     * from the bottom then top of the empty band), not reflow it up next to the top-left item.
     */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorShrinkKeepsBottomRightAnchored() {
        // src 5×9: p1 at top-left (0,0), p2 at bottom-right (4,8). Shrink rows 9→7 (need=2).
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 8, testPackage2, 2, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 5
        idp.numRows = 7
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 5, 7)
        val loc = desktopLocMap()
        // Empty rows 1..7; alternate bottom-first drops rows {7, 1}. p1 keeps (0,0); p2 has 2 dropped
        // rows below row 8 → row 6 (the new bottom row), staying bottom-right — NOT reflowed up.
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(0, 4, 6))
    }

    /**
     * The on-device repro: a FULL axis (no empty column to drop) where the clamp target collides.
     * The overflow item is DROPPED, NOT handed to the solver which would compact it top-left.
     */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorShrinkFullColumnDropsCollidingItem() {
        // src 5×5: row 0 fully occupied cols 0..4 (no empty column). Shrink cols 5→4.
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 1, 0, testPackage2, 2, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 2, 0, testPackage3, 3, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 3, 0, testPackage4, 4, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 0, testPackage5, 5, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 4
        idp.numRows = 4
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 4, 4)
        val loc = desktopLocMap()
        // cols 0..3 fit and stay; col 4 has no empty column to drop → clamps to col 3, which is taken
        // by p4 → collision → p5 is DROPPED (not relocated top-left).
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(0, 1, 0))
        assertThat(loc[testPackage3]).isEqualTo(Triple(0, 2, 0))
        assertThat(loc[testPackage4]).isEqualTo(Triple(0, 3, 0))
        assertThat(loc[testPackage5]).isNull() // dropped, not compacted onto another cell
    }

    /**
     * The real on-device shape: top-left item + lone bottom-right item, shrinking BOTH axes
     * (5×9 → 4×7, like 5×11 → 4×9). The bottom-right item must stay bottom-right, not jump top-left.
     */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorShrinkBottomRightStaysBottomRight() {
        // src 5×9: item top-left (0,0); lone item bottom-right (4,8). Shrink both axes 5×9 → 4×7.
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 4, 8, testPackage2, 2, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 4
        idp.numRows = 7
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 4, 7)
        val loc = desktopLocMap()
        // cols {0,4}: need=1, empties 1..3 → drop col 3 (bottom-first) → col4 shifts to 3. rows {0,8}:
        // need=2, empties 1..7 → drop {7,1} → row8 shifts to 6. Bottom-right stays bottom-right.
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(0, 3, 6))
    }

    /** Each page is preserved/collapsed independently — items never collapse across pages. */
    @Test
    @EnableFlags(Flags.FLAG_GRID_MIGRATION_REFACTOR)
    fun anchorPreservesMultiplePages() {
        addItem(ITEM_TYPE_APPLICATION, 0, CONTAINER_DESKTOP, 0, 0, testPackage1, 1, TMP_TABLE)
        addItem(ITEM_TYPE_APPLICATION, 1, CONTAINER_DESKTOP, 1, 1, testPackage2, 2, TMP_TABLE)
        idp.numDatabaseHotseatIcons = 5
        idp.numColumns = 6
        idp.numRows = 6
        migrateGrid(dbHelper, DbReader(db, TMP_TABLE, context), DbReader(db, TABLE_NAME, context), 5, 5, 6, 6)
        val loc = desktopLocMap()
        assertThat(loc[testPackage1]).isEqualTo(Triple(0, 0, 0))
        assertThat(loc[testPackage2]).isEqualTo(Triple(1, 1, 1)) // stays on page 1, not collapsed to page 0
    }

    private fun desktopLocMap(): Map<String?, Triple<Int, Int, Int>> {
        val c = db.query(
            TABLE_NAME,
            arrayOf(SCREEN, CELLX, CELLY, INTENT),
            "container=$CONTAINER_DESKTOP",
            null, null, null, null,
        ) ?: throw IllegalStateException()
        return parseLocMap(c)
    }

    private fun addItem(
        type: Int,
        screen: Int,
        container: Int,
        x: Int,
        y: Int,
        packageName: String?,
    ): Int {
        return addItem(
            type,
            screen,
            container,
            x,
            y,
            packageName,
            dbHelper.generateNewItemId(),
            TABLE_NAME,
        )
    }

    private fun addItem(
        type: Int,
        screen: Int,
        container: Int,
        x: Int,
        y: Int,
        packageName: String?,
        id: Int,
        tableName: String,
    ): Int {
        val values = ContentValues()
        values.put(_ID, id)
        values.put(CONTAINER, container)
        values.put(SCREEN, screen)
        values.put(CELLX, x)
        values.put(CELLY, y)
        values.put(SPANX, 1)
        values.put(SPANY, 1)
        values.put(ITEM_TYPE, type)
        values.put(INTENT, Intent(Intent.ACTION_MAIN).setPackage(packageName).toUri(0))
        db.insert(tableName, null, values)
        return id
    }
}
