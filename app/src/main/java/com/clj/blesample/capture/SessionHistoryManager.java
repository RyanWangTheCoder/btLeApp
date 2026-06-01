package com.clj.blesample.capture;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.ArrayList;
import java.util.List;

public class SessionHistoryManager {

    private static final String TAG      = "SessionHistoryMgr";
    private static final String FILENAME = "sessions.json";
    private static final int    MAX_ENTRIES = 50;

    private final File file;

    public SessionHistoryManager(Context ctx) {
        file = new File(ctx.getFilesDir(), FILENAME);
    }

    public void append(SessionRecord record) {
        List<SessionRecord> records = loadAll();
        records.add(record);
        if (records.size() > MAX_ENTRIES) {
            records = records.subList(records.size() - MAX_ENTRIES, records.size());
        }
        save(records);
    }

    public List<SessionRecord> loadAll() {
        List<SessionRecord> result = new ArrayList<>();
        if (!file.exists()) return result;
        try {
            char[] buf = new char[(int) file.length()];
            FileReader fr = new FileReader(file);
            fr.read(buf);
            fr.close();
            JSONArray arr = new JSONArray(new String(buf));
            for (int i = 0; i < arr.length(); i++) {
                try {
                    result.add(SessionRecord.fromJson(arr.getJSONObject(i)));
                } catch (Exception e) {
                    Log.w(TAG, "Skipping malformed record at index " + i, e);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to load session history", e);
        }
        return result;
    }

    public void clear() {
        if (file.exists()) file.delete();
    }

    private void save(List<SessionRecord> records) {
        try {
            JSONArray arr = new JSONArray();
            for (SessionRecord r : records) arr.put(r.toJson());
            FileWriter fw = new FileWriter(file, false);
            fw.write(arr.toString());
            fw.close();
        } catch (Exception e) {
            Log.e(TAG, "Failed to save session history", e);
        }
    }
}
