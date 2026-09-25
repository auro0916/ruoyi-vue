package com.ruoyi.framework.service;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.ruoyi.common.utils.file.FileUploadUtils;
import com.ruoyi.common.utils.file.MimeTypeUtils;

import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.PutObjectRequest;

@Service
public class S3FileService
{
    private final S3Client s3Client;

    @Value("${aws.s3.bucket}")
    private String bucketName;

    public S3FileService(S3Client s3Client)
    {
        this.s3Client = s3Client;
    }

    public String upload(MultipartFile file) throws Exception
    {
        // 保留若依原来的文件大小、扩展名校验
        FileUploadUtils.assertAllowed(file, MimeTypeUtils.DEFAULT_ALLOWED_EXTENSION);

        // 保留若依原来的文件命名方式
        String fileName = FileUploadUtils.extractFilename(file);

        // S3 中的对象路径
        String objectKey = "upload/" + fileName;

        PutObjectRequest request = PutObjectRequest.builder()
                .bucket(bucketName)
                .key(objectKey)
                .contentType(file.getContentType())
                .build();

        try
        {
            s3Client.putObject(
                    request,
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize())
            );
        }
        catch (IOException e)
        {
            throw new IOException("Failed to upload file to S3", e);
        }

        return objectKey;
    }
}