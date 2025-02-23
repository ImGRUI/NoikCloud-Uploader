package ru.kelcuprum.waterfiles;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;

public class MultipartParser {

    public static class Part {
        private String name;
        private String fileName;
        private byte[] content;

        // Геттеры и сеттеры
        public String getName() { return name; }
        public void setName(String name) { this.name = name; }
        public String getFileName() { return fileName; }
        public void setFileName(String fileName) { this.fileName = fileName; }
        public byte[] getContent() { return content; }
        public void setContent(byte[] content) { this.content = content; }
    }

    public List<Part> parse(InputStream inputStream, String boundary) throws IOException {
        byte[] bodyBytes = readInputStream(inputStream);
        List<byte[]> partsBytes = splitBody(bodyBytes, boundary);
        return parseParts(partsBytes);
    }

    private byte[] readInputStream(InputStream inputStream) throws IOException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        byte[] data = new byte[1024];
        int bytesRead;
        while ((bytesRead = inputStream.read(data)) != -1) {
            buffer.write(data, 0, bytesRead);
        }
        return buffer.toByteArray();
    }

    private List<byte[]> splitBody(byte[] bodyBytes, String boundary) {
        String delimiter = "--" + boundary;
        byte[] delimiterBytes = delimiter.getBytes(StandardCharsets.UTF_8);
        List<byte[]> parts = new ArrayList<>();

        int start = indexOf(bodyBytes, delimiterBytes, 0);
        while (start != -1) {
            start += delimiterBytes.length;
            int end = indexOf(bodyBytes, delimiterBytes, start);
            if (end == -1) break;

            // Проверка на конечный разделитель
            if (end + delimiterBytes.length < bodyBytes.length && bodyBytes[end + delimiterBytes.length] == '-') {
                break;
            }

            parts.add(Arrays.copyOfRange(bodyBytes, start, end));
            start = end;
        }
        return parts;
    }

    private int indexOf(byte[] array, byte[] target, int fromIndex) {
        for (int i = fromIndex; i <= array.length - target.length; i++) {
            boolean found = true;
            for (int j = 0; j < target.length; j++) {
                if (array[i + j] != target[j]) {
                    found = false;
                    break;
                }
            }
            if (found) return i;
        }
        return -1;
    }

    private List<Part> parseParts(List<byte[]> partsBytes) {
        List<Part> parts = new ArrayList<>();
        for (byte[] partBytes : partsBytes) {
            int headerEnd = indexOf(partBytes, new byte[]{'\r', '\n', '\r', '\n'}, 0);
            if (headerEnd == -1) continue;

            // Чтение заголовков
            String headers = new String(Arrays.copyOfRange(partBytes, 0, headerEnd), StandardCharsets.UTF_8);
            Map<String, String> headersMap = parseHeaders(headers);

            // Извлечение данных
            String contentDisposition = headersMap.get("Content-Disposition");
            if (contentDisposition == null) continue;

            Map<String, String> params = parseContentDisposition(contentDisposition);
            Part part = new Part();
            part.setName(params.get("name"));
            part.setFileName(params.get("filename"));
            part.setContent(Arrays.copyOfRange(partBytes, headerEnd + 4, partBytes.length));
            parts.add(part);
        }
        return parts;
    }

    private Map<String, String> parseHeaders(String headers) {
        Map<String, String> map = new HashMap<>();
        for (String line : headers.split("\r\n")) {
            int idx = line.indexOf(':');
            if (idx != -1) {
                String key = line.substring(0, idx).trim();
                String value = line.substring(idx + 1).trim();
                map.put(key, value);
            }
        }
        return map;
    }

    private Map<String, String> parseContentDisposition(String contentDisposition) {
        Map<String, String> params = new HashMap<>();
        for (String token : contentDisposition.split(";")) {
            token = token.trim();
            if (token.startsWith("form-data")) continue;
            int idx = token.indexOf('=');
            if (idx != -1) {
                String key = token.substring(0, idx).trim();
                String value = token.substring(idx + 1).trim().replaceAll("\"", "");
                params.put(key, value);
            }
        }
        return params;
    }
}