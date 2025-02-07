package ru.kelcuprum.waterfiles;

import com.google.gson.JsonObject;
import express.Express;
import express.middleware.Middleware;
import express.utils.MediaType;
import express.utils.Status;
import ru.kelcuprum.caffeinelib.CoffeeLogger;
import ru.kelcuprum.caffeinelib.config.Config;
import ru.kelcuprum.caffeinelib.utils.GsonHelper;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static ru.kelcuprum.waterfiles.Objects.BAD_REQUEST;
import static ru.kelcuprum.waterfiles.Objects.getErrorObject;

public class Uploader {
    public static Express server;
    public static Config config = new Config("config.json");
    public static File mainFolder = new File("./files");
    public static String html = "";
    public static Config release = new Config(new JsonObject());

    public static void main(String[] args) throws IOException {
        InputStream releaseFile = Uploader.class.getResourceAsStream("/index.html");
        if (releaseFile != null) html = new String(releaseFile.readAllBytes(), StandardCharsets.UTF_8);
        try {
            InputStream kek = Uploader.class.getResourceAsStream("/release.json");
            release = new Config(GsonHelper.parseObject(new String(kek.readAllBytes(), StandardCharsets.UTF_8)));
        } catch (IOException e) {
            LOG.debug(e.getMessage());
        }
        LOG.log("Hello, world!");
        mainFolder = new File(config.getString("folder", "./files"));
        checkFolders();
        server = new Express();
        server.use(Middleware.cors());
        server.use((req, res) -> LOG.log(String.format("%s сделал запрос на %s", req.getIp(), req.getPath())));
        // -=-=-=-=-
        server.all("/:id", (req, res) -> {
            String id = req.getParam("id").split("\\.")[0];
            for (File file : mainFolder.listFiles()) {
                if (file.isFile()) {
                    String name = file.getName().split("\\.")[0];
                    if (name.equals(id)) {
                        res.send(file.toPath());
                        break;
                    }
                }
            }
        });
        server.all("/release", (req, res) -> res.json(release.toJSON()));
        server.post("/upload", (req, res) -> {
            if (req.getHeader("X-File-Name").isEmpty() || req.getBody() == null) {
                res.setStatus(Status._400);
                res.json(BAD_REQUEST);
            } else {
                try {
                    final byte[] bytes = req.getBody().readAllBytes();
                    if (bytes.length > 104857600) {
                        res.setStatus(413);
                        JsonObject error = new JsonObject();
                        error.addProperty("code", 413);
                        error.addProperty("codename", "Payload Too Large");
                        error.addProperty("message", "Файл весит больше 100мб!");
                        JsonObject resp = new JsonObject();
                        resp.add("error", error);
                        res.json(resp);
                    } else {
                        String fileName = req.getHeader("X-File-Name").getFirst();
                        String fileType = fileName.split("\\.").length <= 1 ? "" : "." + fileName.split("\\.")[fileName.split("\\.").length - 1];
                        String id = makeID(7);
                        File file = mainFolder.toPath().resolve(id + fileType).toFile();
                        saveFile(bytes, file);
                        JsonObject resp = new JsonObject();
                        resp.addProperty("id", id);
                        resp.addProperty("url", String.format("%1$s/%2$s", config.getString("url", "https://wfu.kelcu.ru"), id));
                        res.json(resp);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                    res.setStatus(500);
                    res.json(getErrorObject(e));
                }
            }
        });

        server.all("/", (req, res) -> {
            String name = req.getHost().contains("localhost") ? "WaterFiles > Uploader" : req.getHost();
            String page = html;
            File filePage = new File("./index.html");
            if(filePage.exists()){
                try {
                    page = Files.readString(filePage.toPath());
                } catch (Exception ignored){
                    ignored.printStackTrace();
                }
            }
            String resHtml = page.replace("{hostname}", name).replace("{version}", release.getString("version", "1.98.4"));
            res.setContentType(MediaType._html);
            res.send(resHtml);
        });
        server.all((req, res) -> res.send("File not found"));
        server.listen(config.getNumber("port", 1984).intValue());
        LOG.log("-=-=-=-=-=-=-=-=-=-=-=-=-");
        LOG.log("Uploader запущен!");
        LOG.log(String.format("Порт: %s", config.getNumber("port", 1984).intValue()));
        LOG.log("-=-=-=-=-=-=-=-=-=-=-=-=-");
    }

    public static void saveFile(byte[] is, File targetFile) throws IOException {
        Files.write(targetFile.toPath(), is);
    }

    public static String makeID(int length) {
        StringBuilder result = new StringBuilder();
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        int charactersLength = characters.length();
        int counter = 0;
        while (counter < length) {
            result.append(characters.charAt((int) Math.floor(Math.random() * charactersLength)));
            counter += 1;
        }
        return isIDCorrect(result.toString()) ? result.toString() : makeID(length);
    }

    public static boolean isIDCorrect(String id) {
        for (File file : mainFolder.listFiles())
            if (file.isFile())
                if (file.getName().split("\\.")[0].equals(id)) return false;
        return true;
    }

    public static void checkFolders() throws IOException {
        try {
            if (!mainFolder.exists()) Files.createDirectory(mainFolder.toPath());
        } catch (Exception ignored) {
            throw ignored;
        }
    }

    public static CoffeeLogger LOG = new CoffeeLogger("PepeLand Helper");

    static long kilo = 1024;
    static long mega = kilo * kilo;
    static long giga = mega * kilo;
    static long tera = giga * kilo;

    public static String getParsedFileSize(long size) {
        String s;
        double kb = (double) size / kilo;
        double mb = kb / kilo;
        double gb = mb / kilo;
        double tb = gb / kilo;
        if (size < kilo) s = size + " Bytes";
        else if (size < mega) s = String.format("%.2f", kb) + " KB";
        else if (size < giga) s = String.format("%.2f", mb) + " MB";
        else if (size < tera) s = String.format("%.2f", gb) + " GB";
        else s = String.format("%.2f", tb) + " TB";
        return s;
    }
}