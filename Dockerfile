FROM public.ecr.aws/amazoncorretto/amazoncorretto:17

WORKDIR /app

RUN mkdir -p /home/ruoyi/uploadPath

COPY ruoyi-admin/target/ruoyi-admin.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]