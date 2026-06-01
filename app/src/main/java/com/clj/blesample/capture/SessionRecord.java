package com.clj.blesample.capture;

import org.json.JSONException;
import org.json.JSONObject;

public class SessionRecord {
    public long   ts;           // epoch ms
    public int    durationSec;
    public int    swingCount;
    public String fatigueLevel; // "fresh", "moderate", "high", "unknown"
    public float  mdfDropPct;   // -1 = not available

    public SessionRecord(long ts, int durationSec, int swingCount,
                         String fatigueLevel, float mdfDropPct) {
        this.ts           = ts;
        this.durationSec  = durationSec;
        this.swingCount   = swingCount;
        this.fatigueLevel = fatigueLevel;
        this.mdfDropPct   = mdfDropPct;
    }

    public JSONObject toJson() throws JSONException {
        JSONObject o = new JSONObject();
        o.put("ts",           ts);
        o.put("durationSec",  durationSec);
        o.put("swings",       swingCount);
        o.put("fatigueLevel", fatigueLevel);
        o.put("mdfDropPct",   mdfDropPct);
        return o;
    }

    public static SessionRecord fromJson(JSONObject o) throws JSONException {
        return new SessionRecord(
                o.getLong("ts"),
                o.getInt("durationSec"),
                o.getInt("swings"),
                o.optString("fatigueLevel", "unknown"),
                (float) o.optDouble("mdfDropPct", -1.0)
        );
    }
}
