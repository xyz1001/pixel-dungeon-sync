package android.database;
public final class MatrixCursor implements Cursor {
 public final String[] columns; public Object[] values;
 public MatrixCursor(String[] columns, int capacity) { this.columns = columns; }
 public void addRow(Object[] values) { this.values = values; }
}
