package org.json;

public class JSONObject {
    public JSONObject() {}
    public JSONObject(String json) throws Exception {}
    public JSONObject put(String name, boolean value) throws Exception { return this; }
    public JSONObject put(String name, double value) throws Exception { return this; }
    public JSONObject put(String name, int value) throws Exception { return this; }
    public JSONObject put(String name, long value) throws Exception { return this; }
    public JSONObject put(String name, Object value) throws Exception { return this; }
    public String optString(String name, String fallback) { return fallback; }
    public int optInt(String name, int fallback) { return fallback; }
    public long optLong(String name, long fallback) { return fallback; }
    public boolean optBoolean(String name, boolean fallback) { return fallback; }
    public String toString(int indentSpaces) { return ""; }
}
