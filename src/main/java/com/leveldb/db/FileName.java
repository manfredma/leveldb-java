package com.leveldb.db;

/**
 * LevelDB 文件命名规则。对应 C++ db/filename.h + filename.cc。
 */
public final class FileName {
    private FileName() {}

    public enum FileType { LOG, DB_LOCK, TABLE, MANIFEST, CURRENT, TEMP, INFO_LOG, UNKNOWN }

    public static String logFileName(String dbname, long number) {
        return dbname + "/" + String.format("%06d", number) + ".log";
    }
    public static String tableFileName(String dbname, long number) {
        return dbname + "/" + String.format("%06d", number) + ".ldb";
    }
    public static String sstTableFileName(String dbname, long number) {
        return dbname + "/" + String.format("%06d", number) + ".sst";
    }
    public static String manifestFileName(String dbname, long number) {
        return dbname + "/MANIFEST-" + String.format("%06d", number);
    }
    public static String currentFileName(String dbname) { return dbname + "/CURRENT"; }
    public static String lockFileName(String dbname)    { return dbname + "/LOCK"; }
    public static String tempFileName(String dbname, long number) {
        return dbname + "/" + String.format("%06d", number) + ".dbtmp";
    }
    public static String infoLogFileName(String dbname) { return dbname + "/LOG"; }

    public static FileType parseFileName(String path, long[] number) {
        String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
        if (name.equals("CURRENT")) return FileType.CURRENT;
        if (name.equals("LOCK"))    return FileType.DB_LOCK;
        if (name.equals("LOG"))     return FileType.INFO_LOG;
        if (name.startsWith("MANIFEST-")) {
            try { number[0] = Long.parseLong(name.substring(9)); return FileType.MANIFEST; }
            catch (NumberFormatException e) { return FileType.UNKNOWN; }
        }
        int dot = name.lastIndexOf('.');
        if (dot < 0) return FileType.UNKNOWN;
        String base = name.substring(0, dot), ext = name.substring(dot + 1);
        try { number[0] = Long.parseLong(base); }
        catch (NumberFormatException e) { return FileType.UNKNOWN; }
        switch (ext) {
            case "log":   return FileType.LOG;
            case "ldb":   return FileType.TABLE;
            case "sst":   return FileType.TABLE;
            case "dbtmp": return FileType.TEMP;
            default:      return FileType.UNKNOWN;
        }
    }
}
