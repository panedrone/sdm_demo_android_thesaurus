package sdm.android.thesaurus;

import android.content.Context;
import android.content.res.Resources;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteStatement;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import sdm.android.thesaurus.dao.ThesaurusDao;

public class DataStoreManager {

    private static final String DB_NAME = "thesaurus.db";
    private final Context myContext;
    private SQLiteDatabase dataBase;

    public DataStoreManager(Context context) {
        this.myContext = context;
    }

    private String getDatabasePath() {
        // return this.myContext.getDatabasePath(DB_NAME).getAbsolutePath();
        return this.myContext.getDatabasePath(DB_NAME).getPath();
    }

    public void openReadableDatabase() throws SQLException {
        _deploy_database_if_needed();
        String myPath = getDatabasePath();
        dataBase = SQLiteDatabase.openDatabase(myPath, null, SQLiteDatabase.OPEN_READONLY);
    }

    public void openWritableDatabase() throws SQLException {
        _deploy_database_if_needed();
        String myPath = getDatabasePath();
        dataBase = SQLiteDatabase.openDatabase(myPath, null, SQLiteDatabase.OPEN_READWRITE);
    }

    public void beginTransaction() {
        dataBase.beginTransaction();
    }

    public void setTransactionSuccessful() {
        dataBase.setTransactionSuccessful();
    }

    public void endTransaction() {
        // http://stackoverflow.com/questions/6909221/android-sqlite-rollback
        // you do not need to explicitly rollback. If you call
        // db.endTransaction() without .setTransactionSuccessful() it will roll
        // back automatically.
        dataBase.endTransaction();
    }

    public void close() {
        if (dataBase != null) {
            dataBase.close();
            dataBase = null;
        }
    }

    ///////////////////////////////////////////

    private void _deploy_database_if_needed() {
        String myPath = getDatabasePath();
        File f = new File(myPath);
        f.delete();
        if (!f.exists()) {
            try {
                File theDir = new File(f.getParent());
                if (!theDir.exists()) {
                    theDir.mkdir();
                }
                _copy_data_base();
            } catch (IOException e) {
                throw new Error("Error copying database: " + e.getMessage());
            }
        }
    }

    private void _copy_data_base() throws IOException {
        Resources resources = myContext.getResources();
        String myPath = getDatabasePath();
        // it works OK without mkdirs and createNewFile:
        OutputStream databaseOutput = new FileOutputStream(myPath);
        try {
            // Deploying android apps with large databases
            // http://www.chriskopec.com/blog/2010/mar/13/deploying-android-apps-with-large-databases/
            for (int i = 1; i <= 69; i++) {
                String resName = String.format("thesaurus_%03d", i);
                // http://stackoverflow.com/questions/2856407/android-how-to-get-access-to-raw-resources-that-i-put-in-res-folder
                int id = resources.getIdentifier(resName, "raw", myContext.getPackageName());
                // int id = R.raw.thesaurus_zip_001 | i;
                InputStream databaseInput = resources.openRawResource(id);
                try {
                    byte[] buffer = new byte[2048];
                    int length;
                    while ((length = databaseInput.read(buffer)) > 0) {
                        databaseOutput.write(buffer, 0, length);
                        databaseOutput.flush();
                    }
                } finally {
                    databaseInput.close();
                }
            }
        } finally {
            databaseOutput.flush();
            databaseOutput.close();
        }
    }

    /////////////////////////////////////////////////////

    private final MyDataStore ds = new MyDataStore();

    // //////////////////////////////////////////////////
    //
    // use factory:

    public ThesaurusDao createThesaurusDao() {
        return new ThesaurusDao(ds);
    }

    private class MyDataStore extends com.sqldalmaker.DataStore {

        @Override
        public <T> T castGeneratedValue(Class<T> type, Object obj) {
            return type.cast(obj);
        }

        @Override
        public int insert(String sql, String[] genColNames, Object[] genValues, Object... params) throws Exception {
            SQLiteStatement statement = dataBase.compileStatement(sql);
            try {
                _bind_params(statement, params);
                assert statement != null;
                genValues[0] = statement.executeInsert();
            } finally {
                assert statement != null;
                statement.close();
            }
            return 1;
        }

        @Override
        public int execDML(String sql, Object... params) throws Exception {
            SQLiteStatement statement = dataBase.compileStatement(sql);
            try {
                _bind_params(statement, params);
                String trimmed = sql.toUpperCase(Locale.getDefault()).trim();
                if ("DELETE".equals(trimmed) || "UPDATE".equals(trimmed)) {
                    // Returns
                    // the number of rows affected by this SQL statement
                    // execution.
                    assert statement != null;
                    return statement.executeUpdateDelete();
                } else {
                    assert statement != null;
                    statement.execute();
                    return 0;
                }
            } finally {
                assert statement != null;
                statement.close();
            }
        }

        @Override
        public <T> T query(final Class<T> type, String sql, Object... params) throws Exception {
            List<T> res = queryList(type, sql, params);
            if (res.size() == 0) {
                return null;
            }
            if (res.size() > 1) {
                throw new Exception("'More than 1 row found: " + res.size());
            }
            return res.get(0);
        }

        public <T> List<T> queryList(final Class<T> type, String sql, Object... params) throws Exception {
            final List<T> res = new ArrayList<T>();
            final Cursor cursor = dataBase.rawQuery(sql, _get_selection_args(params));
            try {
                if (cursor.moveToFirst()) {
                    do {
                        T t = type.cast(_get_value_by_column_index(cursor, 0));
                        res.add(t);
                    } while (cursor.moveToNext());
                }
            } finally {
                cursor.close();
            }
            return res;
        }

        @Override
        public RowData queryRow(String sql, Object... params) throws Exception {
            final List<RowData> rows = new ArrayList<RowData>();
            queryAllRows(sql, new RowHandler() {
                @Override
                public void handleRow(RowData rd) /*throws Exception*/ {
                    rows.add(rd);
                }
            }, params);
            if (rows.size() == 0) {
                return null;
            }
            if (rows.size() > 1) {
                throw new Exception("'More than 1 row found: " + rows.size());
            }
            return rows.get(0);
        }

        @Override
        public void queryAllRows(String sql, RowHandler rowHandler, Object... params) throws Exception {
            final Cursor cursor = dataBase.rawQuery(sql, _get_selection_args(params));
            try {
                if (cursor.moveToFirst()) {
                    RowData vr = new RowData() {
                        @Override
                        public <VT> VT getValue(Class<VT> type, String columnLabel) throws Exception {
                            return type.cast(_get_value_by_column_label(cursor, columnLabel));
                        }
                    };
                    do {
                        rowHandler.handleRow(vr);
                    } while (cursor.moveToNext());
                }
            } finally {
                cursor.close();
            }
        }

        private void _bind_params(SQLiteStatement statement, Object... params) throws Exception {
            // index The 1-based index to the parameter to bind
            for (int i = 1; i <= params.length; i++) {
                Object param = params[i - 1];
                if (param instanceof Integer) {
                    statement.bindLong(i, (Integer) param);
                } else if (param instanceof Long) {
                    statement.bindLong(i, (Long) param);
                } else if (param instanceof Double) {
                    statement.bindDouble(i, (Double) param);
                } else if (param instanceof String) {
                    statement.bindString(i, (String) param);
                } else if (param instanceof byte[]) {
                    statement.bindBlob(i, (byte[]) param);
                } else if (param == null) {
                    statement.bindNull(i);
                } else {
                    throw new Exception("Unexpected param type: " + param.getClass().getName());
                }
            }
        }

        private String[] _get_selection_args(Object[] params) {
            if (params == null) {
                return null;
            }
            String[] res = new String[params.length];
            for (int i = 0; i < params.length; i++) {
                Object param = params[i];
                if (param == null) {
                    res[i] = null;
                } else {
                    res[i] = param.toString();
                }
            }
            return res;
        }

        private Object _get_value_by_column_label(Cursor cursor, String columnLabel) throws Exception {
            // columnIndex the zero-based index of the target column.
            int columnIndex = cursor.getColumnIndexOrThrow(columnLabel);
            return _get_value_by_column_index(cursor, columnIndex);
        }

        private Object _get_value_by_column_index(Cursor cursor, int columnIndex) throws Exception {
            Object res;
            int type = cursor.getType(columnIndex);
            switch (type) {
                case Cursor.FIELD_TYPE_NULL:
                    res = null;
                    break;
                case Cursor.FIELD_TYPE_INTEGER:
                    res = cursor.getLong(columnIndex);
                    break;
                case Cursor.FIELD_TYPE_FLOAT:
                    res = cursor.getDouble(columnIndex);
                    break;
                case Cursor.FIELD_TYPE_STRING:
                    res = cursor.getString(columnIndex);
                    break;
                case Cursor.FIELD_TYPE_BLOB:
                    res = cursor.getBlob(columnIndex);
                    break;
                default:
                    throw new Exception("Unexpected value type: " + type);
            }
            return res;
        }
    }
}