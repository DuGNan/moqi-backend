package com.dugnan.moqi;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.SpringApplication;

@SpringBootApplication
@MapperScan("com.dugnan.moqi.**.mapper")
public class MoqiStartApplication {

    public static void main(String[] args) {
        SpringApplication.run(MoqiStartApplication.class, args);
    }
}
