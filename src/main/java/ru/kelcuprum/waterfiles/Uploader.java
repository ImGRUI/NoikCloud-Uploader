package ru.kelcuprum.waterfiles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import express.Express;
import express.http.request.Request;
import express.http.response.Response;
import express.middleware.FileStatics;
import express.middleware.Middleware;
import express.utils.MediaType;
import express.utils.Status;
import ru.kelcuprum.caffeinelib.CoffeeLogger;
import ru.kelcuprum.caffeinelib.config.Config;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;

public class Uploader {
    public static Express server;
    public static Config config = new Config("./config.json");
    public static Config links = new Config("./files.json");
    public static File mainFolder = new File("./files");
    public static String videoHtml = "";
    public static String audioHtml = "";
    public static String somethingHtml = "";
    public static HashMap<String, String> fileNames = new HashMap<>();
    public static HashMap<String, String> fileDeletes = new HashMap<>();
    public static HashMap<String, String> fileTypes = new HashMap<>();
    public static HashMap<String, File> files = new HashMap<>();

    public static void main(String[] args) throws IOException {
        try (InputStream releaseFile = Uploader.class.getResourceAsStream("/index.html")) {
            if (releaseFile != null) somethingHtml = new String(releaseFile.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream releaseFile = Uploader.class.getResourceAsStream("/video.html")) {
            if (releaseFile != null) videoHtml = new String(releaseFile.readAllBytes(), StandardCharsets.UTF_8);
        }
        try (InputStream releaseFile = Uploader.class.getResourceAsStream("/audio.html")) {
            if (releaseFile != null) audioHtml = new String(releaseFile.readAllBytes(), StandardCharsets.UTF_8);
        }
        JsonArray h = links.getJsonArray("names", new JsonArray());
        links.load();
        for(JsonElement element : h) {
            fileNames.put(((JsonObject) element).get("id").getAsString(), ((JsonObject) element).get("name").getAsString());
            if (((JsonObject) element).has("delete") && !((JsonObject) element).get("delete").isJsonNull())
                fileDeletes.put(((JsonObject) element).get("id").getAsString(), ((JsonObject) element).get("delete").getAsString());
            if (((JsonObject) element).has("type") && !((JsonObject) element).get("type").isJsonNull())
                fileTypes.put(((JsonObject) element).get("id").getAsString(), ((JsonObject) element).get("type").getAsString());
        }
        LOG.log("Hello, world!");
        mainFolder = new File(config.getString("folder", "./files"));
        checkFolders();
        CacheFiles();
        server = new Express();
        server.use(Middleware.cors());
        server.use((req, res) -> LOG.log(String.format("%s made request to %s", req.getIp(), req.getPath())));
        // -=-=-=-=-
        server.all("/:id", (req, res) -> {
            String id = req.getParam("id").split("\\.")[0];
            File file = getFileByID(id);
            if (file != null) {
                String name = file.getName().split("\\.")[0];
                if (name.equals(id)) {
                    if (fileTypes.containsKey(name)) {
                        String FileType = fileTypes.get(name);
                        if (FileType.startsWith("video")) {
                            sendHtml(videoHtml, "./video.html", id, fileNames.get(name), res);
                        }
                        if (FileType.startsWith("audio")) {
                            sendHtml(audioHtml, "./audio.html", id, fileNames.get(name), res);
                        }
                        if (fileNames.containsKey(name)) {
                            String encoded = URLEncoder.encode(fileNames.get(name), StandardCharsets.UTF_8);
                            encoded = encoded.replace("+", "%20");
                            res.setHeader("Content-Disposition", "filename*=UTF-8''" + encoded);
                        }
                        res.setContentType(FileType);
                        if (FileType.startsWith("text") || FileType.startsWith("application/xhtml") || FileType.startsWith("multipart/related") || FileType.startsWith("application/javascript") || FileType.startsWith("application/xml") || FileType.startsWith("message/rfc822")) {
                            res.setContentType("text/plain; charset=UTF-8");
                        }
                        if (FileType.startsWith("image/svg")) {
                            res.setContentType("application/octet-stream");
                        }
                    }
                    res.send(file.toPath());
                }
            }
        });
        server.all("/raw/:id", (req, res) -> {
            String id = req.getParam("id").split("\\.")[0];
            File file = getFileByID(id);
            if (file != null) {
                String name = file.getName().split("\\.")[0];
                if (name.equals(id)) {
                    if (fileNames.containsKey(name)) {
                        String encoded = URLEncoder.encode(fileNames.get(name), StandardCharsets.UTF_8);
                        encoded = encoded.replace("+", "%20");
                        res.setHeader("Content-Disposition", "filename*=UTF-8''" + encoded);
                    }
                    if (fileTypes.containsKey(name)) {
                        String FileType = fileTypes.get(name);
                        if (FileType.startsWith("video") || FileType.startsWith("audio")) {
                            chromeCompat(file,req,res);
                            res.send(file.toPath());
                        }
                    }
                }
            }
        });
        server.all("/", (req, res) -> {
            res.setContentType(MediaType._html);
            res.send(somethingHtml);
        });
        boolean staticEnable = true;
        Path staticPath = Path.of("./static");
        if (!staticPath.toFile().exists()) {
            try {
                Files.createDirectory(staticPath);
            } catch (Exception ex){
                ex.printStackTrace();
                staticEnable = false;
            }
        }
        if (staticEnable) server.all(new FileStatics("./static"));
        server.all((req, res) -> {
            res.setStatus(Status._404);
            res.send("File not found");
        });
        server.listen(config.getNumber("port", 8419).intValue());
        LOG.log("-=-=-=-=-=-=-=-=-=-=-=-=-");
        LOG.log("Archive started");
        LOG.log(String.format("Port: %s", config.getNumber("port", 8419).intValue()));
        LOG.log("-=-=-=-=-=-=-=-=-=-=-=-=-");
    }

    public static void checkFolders() throws IOException {
        if (!mainFolder.exists()) Files.createDirectory(mainFolder.toPath());
    }

    public static void CacheFiles() {
        for (File file : mainFolder.listFiles()) {
            if (file.isFile()) {
                String id = file.getName().split("\\.")[0];
                files.put(id, file);
            }
        }
    }

    public static File getFileByID (String id) {
        File needFile;
        needFile = files.get(id);
        if (needFile != null && !needFile.exists()) {
            files.remove(id);
            needFile = null;
        }
        return needFile;
    }

    public static void sendHtml(String html, String htmlType, String id, String fileName, Response res) {
        if (fileName == null || fileName.isEmpty()) fileName = id;
        String page = html;
        File filePage = new File(htmlType);
        if (filePage.exists()) {
            try {
                page = Files.readString(filePage.toPath());
            } catch (Exception ignored) {
                ignored.printStackTrace();
            }
        }
        String resHtml = page.replace("{SOURCE_ID}", id).replace("{TITLE}", fileName);
        res.setContentType(MediaType._html);
        res.send(resHtml);
    }

    public static void chromeCompat(File file, Request req, Response res) {
        res.setHeader("accept-ranges", "bytes");
        if (!req.getHeader("range").isEmpty())
            res.setHeader("content-range", "bytes " + req.getHeader("range").getFirst() + file.length() + "/" + (file.length() + 1));
    }

    public static CoffeeLogger LOG = new CoffeeLogger("Archive/Uploader");
}