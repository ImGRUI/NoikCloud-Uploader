package ru.kelcuprum.waterfiles;

import com.google.gson.JsonObject;
import ru.kelcuprum.caffeinelib.utils.GsonHelper;

public class Objects {
    public static JsonObject NOT_FOUND = GsonHelper.parseObject("{\"error\":{\"code\":404,\"codename\":\"Not found\",\"message\":\"Method not found\"}}");
    public static JsonObject INTERNAL_SERVER_ERROR = GsonHelper.parseObject("{\"error\":{\"code\":500,\"codename\":\"Internal Server Error\",\"message\":\"\"}}");
    public static JsonObject BAD_REQUEST = GsonHelper.parseObject("{\"error\": {\"code\": 400,\"codename\": \"Bad Request\",\"message\": \"The required arguments are missing!\"}}");
    public static JsonObject getErrorObject(Exception ex){
        JsonObject object = INTERNAL_SERVER_ERROR;
        object.get("error").getAsJsonObject().addProperty("message", ex.getMessage() == null ? ex.getClass().toString() : ex.getMessage());
        return object;
    }
}
