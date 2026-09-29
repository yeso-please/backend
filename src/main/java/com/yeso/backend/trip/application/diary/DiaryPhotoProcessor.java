package com.yeso.backend.trip.application.diary;

import com.drew.imaging.ImageMetadataReader;
import com.drew.metadata.Metadata;
import com.drew.metadata.exif.ExifSubIFDDirectory;
import com.drew.metadata.exif.GpsDirectory;
import com.yeso.backend.shared.exception.ErrorCode;
import com.yeso.backend.trip.domain.DiaryException;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Iterator;

@Component
public class DiaryPhotoProcessor {

    public static final long MAX_FILE_SIZE = 10L * 1024 * 1024;
    private static final long MAX_PIXEL_COUNT = 40_000_000L;
    private static final int MAX_SIDE = 12_000;
    private static final int MAX_THUMBNAIL_SIDE = 640;

    public ProcessedDiaryPhoto process(byte[] bytes, String declaredContentType) {
        if (bytes == null || bytes.length == 0 || bytes.length > MAX_FILE_SIZE) {
            throw invalid("파일 크기가 허용 범위를 벗어났습니다.");
        }
        try {
            String format;
            int width;
            int height;
            BufferedImage image;
            try (ImageInputStream input = ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                if (input == null) throw invalid("이미지를 읽을 수 없습니다.");
                Iterator<ImageReader> readers = ImageIO.getImageReaders(input);
                if (!readers.hasNext()) throw invalid("지원하지 않는 이미지 형식입니다.");
                ImageReader reader = readers.next();
                try {
                    reader.setInput(input, true, true);
                    format = reader.getFormatName().toLowerCase();
                    width = reader.getWidth(0);
                    height = reader.getHeight(0);
                    if (width <= 0 || height <= 0 || width > MAX_SIDE || height > MAX_SIDE
                            || (long) width * height > MAX_PIXEL_COUNT) {
                        throw invalid("이미지 해상도가 허용 범위를 벗어났습니다.");
                    }
                    image = reader.read(0);
                } finally {
                    reader.dispose();
                }
            }
            String contentType = switch (format) {
                case "jpeg", "jpg" -> "image/jpeg";
                case "png" -> "image/png";
                case "webp" -> "image/webp";
                default -> throw invalid("JPEG, PNG, WebP 이미지만 업로드할 수 있습니다.");
            };
            if (declaredContentType != null && !declaredContentType.isBlank()
                    && !declaredContentType.equalsIgnoreCase(contentType)
                    && !(contentType.equals("image/jpeg") && declaredContentType.equalsIgnoreCase("image/jpg"))) {
                throw invalid("파일 내용과 Content-Type이 일치하지 않습니다.");
            }
            LocalDateTime takenAt = null;
            Double lat = null;
            Double lng = null;
            try {
                Metadata metadata = ImageMetadataReader.readMetadata(new ByteArrayInputStream(bytes));
                ExifSubIFDDirectory exif = metadata.getFirstDirectoryOfType(ExifSubIFDDirectory.class);
                if (exif != null && exif.getDateOriginal() != null) {
                    takenAt = exif.getDateOriginal().toInstant().atZone(ZoneId.of("Asia/Seoul")).toLocalDateTime();
                }
                GpsDirectory gps = metadata.getFirstDirectoryOfType(GpsDirectory.class);
                if (gps != null && gps.getGeoLocation() != null) {
                    lat = gps.getGeoLocation().getLatitude();
                    lng = gps.getGeoLocation().getLongitude();
                }
            } catch (Exception ignored) {
                // Metadata is optional; invalid EXIF must not reject an otherwise decodable image.
            }
            byte[] sanitized = encode(image, format);
            byte[] thumbnail = encodeThumbnail(image);
            return new ProcessedDiaryPhoto(sanitized, thumbnail, contentType, takenAt, lat, lng);
        } catch (DiaryException exception) {
            throw exception;
        } catch (IOException | RuntimeException exception) {
            throw invalid("이미지 파일을 처리할 수 없습니다.");
        }
    }

    private static byte[] encode(BufferedImage image, String format) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        if (!ImageIO.write(image, format, output)) throw new IOException("No image writer");
        return output.toByteArray();
    }

    private static byte[] encodeThumbnail(BufferedImage source) throws IOException {
        double scale = Math.min(1.0, (double) MAX_THUMBNAIL_SIDE / Math.max(source.getWidth(), source.getHeight()));
        int width = Math.max(1, (int) Math.round(source.getWidth() * scale));
        int height = Math.max(1, (int) Math.round(source.getHeight() * scale));
        BufferedImage thumbnail = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = thumbnail.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            graphics.drawImage(source, 0, 0, width, height, null);
        } finally {
            graphics.dispose();
        }
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        ImageIO.write(thumbnail, "jpeg", output);
        return output.toByteArray();
    }

    private static DiaryException invalid(String message) {
        return new DiaryException(ErrorCode.DIARY_PHOTO_INVALID, message);
    }
}
