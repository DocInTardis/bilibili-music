package com.example.bilibilimusic;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.example.bilibilimusic.mapper")
public class BilibiliMusicApplication {

    public static void main(String[] args) {
        SpringApplication.run(BilibiliMusicApplication.class, args);
    }
}
