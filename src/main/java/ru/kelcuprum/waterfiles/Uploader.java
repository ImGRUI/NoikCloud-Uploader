package ru.kelcuprum.waterfiles;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import express.Express;
import express.middleware.FileStatics;
import express.middleware.Middleware;
import express.utils.MediaType;
import express.utils.Status;
import org.apache.commons.fileupload.FileItem;
import org.apache.commons.fileupload.FileUploadBase;
import org.apache.commons.fileupload.RequestContext;
import org.apache.commons.fileupload.disk.DiskFileItemFactory;
import org.apache.commons.fileupload.servlet.ServletFileUpload;
import org.apache.commons.io.FilenameUtils;
import org.apache.tika.Tika;
import ru.kelcuprum.caffeinelib.CoffeeLogger;
import ru.kelcuprum.caffeinelib.config.Config;

import java.io.*;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.security.SecureRandom;
import java.util.Map;

import static ru.kelcuprum.waterfiles.Objects.*;

public class Uploader {
    public static Express server;
    public static Config config = new Config("./config.json");
    public static Config links = new Config("./files.json");
    public static File mainFolder = new File("./files");
    public static String folder = "./files";
    public static String html = "";
    public static HashMap<String, String> fileNames = new HashMap<>();
    public static HashMap<String, String> fileDeletes = new HashMap<>();
    public static HashMap<String, String> fileTypes = new HashMap<>();
    public static HashMap<String, File> files = new HashMap<>();
    public static final SecureRandom SECURE_RANDOM = new SecureRandom();
    public static final Tika tika = new Tika();
    public static final int Threshold = 1024 * 1024 * 10;
    public static final int MaxFileSize = 1024 * 1024 * 100;
    public static final int MaxRequestSize = 1024 * 1024 * 100;
    public static final byte CACHE_SIZE = 5;
    public static final byte MIN_REQUESTS = 2;
    public static final Map<String, Integer> requestCountMap = new HashMap<>();
    public static final LinkedHashMap<String, byte[]> fileContentCache = new LinkedHashMap<>(CACHE_SIZE + 1, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, byte[]> eldest) {
            boolean shouldRemove = size() > CACHE_SIZE;
            if (shouldRemove) {
                String removed = eldest.getKey();
                synchronized (requestCountMap) {
                    requestCountMap.remove(removed);
                }
            }
            return shouldRemove;
        }
    };

    public static void main(String[] args) throws IOException {
        try (InputStream releaseFile = Uploader.class.getResourceAsStream("/index.html")) {
            if (releaseFile != null) html = new String(releaseFile.readAllBytes(), StandardCharsets.UTF_8);
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
                String encoded = URLEncoder.encode(fileNames.get(name), StandardCharsets.UTF_8);
                encoded = encoded.replace("+", "%20");
                if (name.equals(id)) {
                    boolean cache = false;
                    byte[] cachedContent;
                    int currentCount;
                    synchronized (fileContentCache) {
                        if (fileContentCache.containsKey(id)) {
                            cache = true;
                            cachedContent = fileContentCache.get(id);
                        } else {
                            currentCount = requestCountMap.getOrDefault(id, 0) + 1;
                            requestCountMap.put(id, currentCount);
                            cachedContent = fileContentCache.get(id);
                            if (currentCount > MIN_REQUESTS) {
                                try {
                                    System.out.println("Writing Cache to " + id);
                                    cachedContent = Files.readAllBytes(file.toPath());
                                    fileContentCache.put(id, cachedContent);
                                } catch (IOException e) {
                                    LOG.log("Failed to cache " + id + e.getMessage());
                                }
                            }
                            if (cachedContent != null) {
                                cache = true;
                            }
                        }
                    }
                    if (fileNames.containsKey(name))
                        res.setHeader("Content-Disposition", "filename*=UTF-8''" + encoded);
                    if (fileTypes.containsKey(name)) {
                        res.setContentType(fileTypes.get(name));
                        if (fileTypes.get(name).startsWith("video") || fileTypes.get(name).startsWith("audio")) {
                            res.setHeader("accept-ranges", "bytes");
                            if (!req.getHeader("range").isEmpty())
                                if (cache) res.setHeader("content-range", "bytes " + req.getHeader("range").getFirst() + cachedContent.length + "/" + (cachedContent.length + 1));
                                else res.setHeader("content-range", "bytes " + req.getHeader("range").getFirst() + file.length() + "/" + (file.length() + 1));
                        }
                        if (fileTypes.get(name).startsWith("text")) {
                            res.setContentType(fileTypes.get(name) + "; charset=UTF-8");
                        }
                    }
                    if (cache) {
                        res.sendBytes(cachedContent, res.getContentType());
                        System.out.println("Serving from cache");
                    } else {
                        res.send(file.toPath());
                    }
                }
            }
        });
        server.post("/upload", (req, res) -> {
            RequestContext request = new RequestContext() {
                public String getContentType() {
                    return req.getContentType();
                }

                public int getContentLength() {
                    if (req.getContentLength() > 2147483647) {
                        return 2147483647;
                    } else return (int) req.getContentLength();
                }

                public String getCharacterEncoding() {
                    return "UTF-8";
                }

                public InputStream getInputStream() {
                    return req.getBody();
                }
            };
            if (!ServletFileUpload.isMultipartContent(request)) {
                System.out.println("Is_Multipart_400");
                res.setStatus(Status._400);
                res.json(BAD_REQUEST);
                return;
            }
            DiskFileItemFactory factory = new DiskFileItemFactory();
            factory.setSizeThreshold(Threshold);
            factory.setRepository(new File(System.getProperty("java.io.tmpdir")));
            ServletFileUpload upload = new ServletFileUpload(factory);
            upload.setHeaderEncoding("UTF-8");
            upload.setFileSizeMax(MaxFileSize);
            upload.setSizeMax(MaxRequestSize);
            try {
                List<FileItem> formItems = upload.parseRequest(request);
                if (formItems == null || formItems.isEmpty()) {
                    System.out.println("Null_Form_Items_400");
                    res.setStatus(Status._400);
                    res.json(BAD_REQUEST);
                } else {
                    byte count = 0;
                    for (FileItem item : formItems) {
                        if (!item.isFormField()) {
                            if (count == 1) {
                                System.out.println("Too_Much_Files");
                                res.setStatus(Status._400);
                                res.json(COUNT);
                                return;
                            } else {
                                count++;
                            }
                        }
                    }
                    for (FileItem item : formItems) {
                        if (!item.isFormField()) {
                            try (InputStream inputStream = item.getInputStream()) {
                                String fileName = new File(item.getName()).getName();
                                String fileTypeMedia = tika.detect(inputStream, fileName);
                                String extension = FilenameUtils.getExtension(fileName);
                                String fileType = extension.isEmpty() ? "" : "." + extension;
                                String id = makeID(7, false);
                                String delete_id = makeID(21, true);
                                File storeFile = new File(folder, id + fileType);
                                item.write(storeFile);
                                files.put(id, storeFile);
                                addFilename(id, fileName, delete_id, fileTypeMedia);
                                JsonObject resp = new JsonObject();
                                resp.addProperty("id", id);
                                resp.addProperty("type", fileTypeMedia);
                                resp.addProperty("ext", extension);
                                resp.addProperty("url", String.format("%1$s/%2$s", config.getString("url", "https://noikcloud.xyz"), id));
                                resp.addProperty("delete_url", String.format("%1$s/delete/%2$s", config.getString("url", "https://noikcloud.xyz"), delete_id));
                                res.json(resp);
                            } catch (Exception e) {
                                System.out.println("Parse_500");
                                e.printStackTrace();
                                res.setStatus(Status._500);
                                res.json(getErrorObject(e));
                                break;
                            } finally {
                                item.delete();
                                System.out.println("Success");
                            }
                        }
                    }
                }
            } catch (FileUploadBase.SizeLimitExceededException e) {
                System.out.println("Size_413");
                res.setStatus(Status._413);
                res.json(PAYLOAD);
            } catch (Exception e) {
                System.out.println("500");
                e.printStackTrace();
                res.setStatus(Status._500);
                res.json(getErrorObject(e));
            }
        });
        server.all("/delete/:id", (req, res) -> {
            String id = req.getParam("id");
            String idFile = "";
            for (String name : fileDeletes.keySet()) {
                if (fileDeletes.get(name).contains(id)) idFile = name;
            }
            if (fileDeletes.containsValue(id)) {
                File file = getFileByID(idFile);
                if (file != null) {
                    String name = file.getName().split("\\.")[0];
                    if (name.equals(idFile)) {
                        synchronized (fileContentCache) {
                            fileContentCache.remove(idFile);
                            requestCountMap.remove(idFile);
                        }
                        file.delete();
                        fileNames.remove(idFile);
                        fileDeletes.remove(idFile);
                        files.remove(idFile, file);
                        res.send("File deleted");
                    }
                }
            } else {
                res.setStatus(Status._404);
                res.json(NOT_FOUND);
            }
        });
        server.all("/", (req, res) -> {
            String name = config.getString("name", "{host}")
                    .replace("{host}", req.getHost().contains("localhost") || req.getHost().contains("127.0.0.1") ? "NoikCloud > Uploader" : req.getHost());
            String page = html;
            File filePage = new File("./index.html");
            if (filePage.exists()) {
                try {
                    page = Files.readString(filePage.toPath());
                } catch (Exception ignored) {
                    ignored.printStackTrace();
                }
            }
            String resHtml = page.replace("{hostname}", name)
                    .replace("{accent_color}", config.getString("accent_color", "#bf6a6a"))
                    .replace("{delete_color}", config.getString("delete_color", "#652c2d"));
            res.setContentType(MediaType._html);
            res.send(resHtml);
        });
        boolean staticEnable = true;
        if (!Path.of("./static").toFile().exists()) {
            try {
                Files.createDirectory(Path.of("./static"));
            } catch (Exception ex){
                ex.printStackTrace();
                staticEnable = false;
            }
        }
        if (staticEnable) server.all(new FileStatics("static"));
        server.all((req, res) -> {
            res.setStatus(Status._404);
            res.send("File not found");
        });
        server.listen(config.getNumber("port", 1984).intValue());
        LOG.log("-=-=-=-=-=-=-=-=-=-=-=-=-");
        LOG.log("Uploader started");
        LOG.log(String.format("Port: %s", config.getNumber("port", 1984).intValue()));
        LOG.log("-=-=-=-=-=-=-=-=-=-=-=-=-");
    }
    public static void addFilename(String id, String name, String delete_id, String file_type_media) {
        fileNames.put(id, name);
        fileDeletes.put(id, delete_id);
        fileTypes.put(id, file_type_media);
        saveFilenames();
    }
    public static void saveFilenames() {
        JsonArray j = new JsonArray();
        for (String key : fileNames.keySet()) {
            JsonObject jj = new JsonObject();
            jj.addProperty("id", key);
            jj.addProperty("name", fileNames.get(key));
            jj.addProperty("type", fileTypes.get(key));
            if (fileDeletes.containsKey(key))
                jj.addProperty("delete", fileDeletes.get(key));
            j.add(jj);
        }
        links.setJsonArray("names", j);
        links.save();
    }

    public static String makeID(int length, boolean isDelete) {
        StringBuilder result = new StringBuilder();
        String characters = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-";
        int charactersLength = characters.length();
        int counter = 0;
        while (counter < length) {
            result.append(characters.charAt(SECURE_RANDOM.nextInt(charactersLength)));
            counter += 1;
        }
        return isIDCorrect(result.toString(), isDelete) ? result.toString() : makeID(length, isDelete);
    }

    public static boolean isIDCorrect(String id, boolean isDelete) {
        if (!isDelete) {
            File file = getFileByID(id);
            if (file != null)
                return !file.getName().split("\\.")[0].equals(id);
        } else return !fileDeletes.containsKey(id);
        return true;
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

    public static CoffeeLogger LOG = new CoffeeLogger("NoikCloud/Uploader");
}