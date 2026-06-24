package com.jason.demo.demo2;

import org.springframework.ai.vectorstore.milvus.autoconfigure.MilvusVectorStoreAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(exclude = MilvusVectorStoreAutoConfiguration.class)
public class Demo2Application {

    public static void main(String[] args) {
        SpringApplication.run(Demo2Application.class, args);
    }

}
