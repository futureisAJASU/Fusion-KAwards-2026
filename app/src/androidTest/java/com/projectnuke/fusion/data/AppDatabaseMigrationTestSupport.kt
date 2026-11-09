package com.projectnuke.fusion.data

import androidx.room.migration.Migration

/** Complete production migration chain for tests that reopen fixtures through Room. */
internal fun appDatabaseMigrationsForTest(): Array<Migration> = arrayOf(
    AppDatabase.Migration1To2,
    AppDatabase.Migration2To3,
    AppDatabase.Migration3To4,
    AppDatabase.Migration4To5,
    AppDatabase.Migration5To6,
    AppDatabase.Migration6To7,
    AppDatabase.Migration7To8,
    AppDatabase.Migration8To9,
    AppDatabase.Migration9To10,
    AppDatabase.Migration10To11,
    AppDatabase.Migration11To12,
    AppDatabase.Migration12To13,
    AppDatabase.Migration13To14,
    AppDatabase.Migration14To15,
    AppDatabase.Migration15To16,
    AppDatabase.Migration16To17,
    AppDatabase.Migration17To18,
    AppDatabase.Migration18To19,
    AppDatabase.Migration19To20,
    AppDatabase.Migration20To21,
    AppDatabase.Migration21To22,
    AppDatabase.Migration22To23,
    AppDatabase.Migration23To24,
    AppDatabase.Migration24To25,
    AppDatabase.Migration25To26,
    AppDatabase.Migration26To27,
    AppDatabase.Migration27To28,
    AppDatabase.Migration28To29,
    AppDatabase.Migration29To30,
    AppDatabase.Migration30To31,
)
