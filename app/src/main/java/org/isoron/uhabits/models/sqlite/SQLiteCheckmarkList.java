/*
 * Copyright (C) 2016 Álinson Santos Xavier <isoron@gmail.com>
 *
 * This file is part of Loop Habit Tracker.
 *
 * Loop Habit Tracker is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your
 * option) any later version.
 *
 * Loop Habit Tracker is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <http://www.gnu.org/licenses/>.
 */

package org.isoron.uhabits.models.sqlite;

import android.database.sqlite.*;
import android.support.annotation.*;
import android.support.annotation.Nullable;

import com.activeandroid.*;

import org.isoron.uhabits.models.*;
import org.isoron.uhabits.models.sqlite.records.*;
import org.isoron.uhabits.utils.*;
import org.jetbrains.annotations.*;

import java.util.*;

/**
 * Implementation of a {@link CheckmarkList} that is backed by SQLite.
 */
public class SQLiteCheckmarkList extends CheckmarkList
{

    private static final String ADD_QUERY =
        "insert into Checkmarks(habit, timestamp, value) values (?,?,?)";

    private static final String INVALIDATE_QUERY =
        "delete from Checkmarks where habit = ? and timestamp >= ?";

    @Nullable
    private HabitRecord habitRecord;

    @NonNull
    private final SQLiteUtils<CheckmarkRecord> sqlite;

    @Nullable
    private CachedData cache;

    @NonNull
    private final SQLiteStatement invalidateStatement;

    @NonNull
    private final SQLiteStatement addStatement;

    @NonNull
    private final SQLiteDatabase db;

    public SQLiteCheckmarkList(Habit habit)
    {
        super(habit);
        sqlite = new SQLiteUtils<>(CheckmarkRecord.class);

        db = Cache.openDatabase();
        addStatement = db.compileStatement(ADD_QUERY);
        invalidateStatement = db.compileStatement(INVALIDATE_QUERY);
    }

    @Override
    public void add(List<Checkmark> checkmarks)
    {
        check(habit.getId());
        db.beginTransaction();
        try
        {
            for (Checkmark c : checkmarks)
            {
                addStatement.bindLong(1, habit.getId());
                addStatement.bindLong(2, c.getTimestamp());
                addStatement.bindLong(3, c.getValue());
                addStatement.execute();
            }

            db.setTransactionSuccessful();
        }
        finally
        {
            db.endTransaction();
        }
    }

    @NonNull
    @Override
    public List<Checkmark> getByInterval(long fromTimestamp, long toTimestamp)
    {
        check(habit.getId());
        compute(fromTimestamp, toTimestamp);

        String query = "select habit, timestamp, value from checkmarks " +
                       "where habit = ? and timestamp >= ? and timestamp <= ? " +
                       "order by timestamp desc";

        String params[] = {
            Long.toString(habit.getId()),
            Long.toString(fromTimestamp),
            Long.toString(toTimestamp)
        };

        List<CheckmarkRecord> records = sqlite.query(query, params);
        for (CheckmarkRecord record : records) record.habit = habitRecord;

        int nDays = DateUtils.getDaysBetween(fromTimestamp, toTimestamp) + 1;
        if (records.size() != nDays)
        {
            throw new InconsistentDatabaseException(
                String.format("habit=%s, %d expected, %d found",
                    habit.getName(), nDays, records.size()));
        }

        return toCheckmarks(records);
    }

    @Override
    public int getTodayValue()
    {
        if (cache == null || cache.expired())
            cache = new CachedData(super.getTodayValue());

        return cache.todayValue;
    }

    @Override
    public void invalidateNewerThan(long timestamp)
    {
        cache = null;
        invalidateStatement.bindLong(1, habit.getId());
        invalidateStatement.bindLong(2, timestamp);
        invalidateStatement.execute();
        observable.notifyListeners();
    }

    @Override
    @Nullable
    protected Checkmark getNewestComputed()
    {
        check(habit.getId());
        String query = "select habit, timestamp, value from checkmarks " +
                       "where habit = ? " + "order by timestamp desc " +
                       "limit 1";

        String params[] = { Long.toString(habit.getId()) };
        return getSingleCheckmarkFromQuery(query, params);
    }

    @Override
    @Nullable
    protected Checkmark getOldestComputed()
    {
        check(habit.getId());
        String query = "select habit, timestamp, value from checkmarks " +
                       "where habit = ? " + "order by timestamp asc " +
                       "limit 1";

        String params[] = { Long.toString(habit.getId()) };
        return getSingleCheckmarkFromQuery(query, params);
    }

    @Contract("null -> fail")
    private void check(Long id)
    {
        if (id == null) throw new RuntimeException("habit is not saved");
        if (habitRecord != null) return;

        habitRecord = HabitRecord.get(id);
        if (habitRecord == null) throw new RuntimeException("habit not found");
    }

    @Nullable
    private Checkmark getSingleCheckmarkFromQuery(String query, String params[])
    {
        CheckmarkRecord record = sqlite.querySingle(query, params);
        if (record == null) return null;
        record.habit = habitRecord;
        return record.toCheckmark();
    }

    @NonNull
    private List<Checkmark> toCheckmarks(@NonNull List<CheckmarkRecord> records)
    {
        List<Checkmark> checkmarks = new LinkedList<>();
        for (CheckmarkRecord r : records) checkmarks.add(r.toCheckmark());
        return checkmarks;
    }

    private static class CachedData
    {
        int todayValue;

        private long today;

        CachedData(int todayValue)
        {
            this.todayValue = todayValue;
            this.today = DateUtils.getStartOfToday();
        }

        boolean expired()
        {
            return today != DateUtils.getStartOfToday();
        }
    }
}
