/**
 * @author dgn
 * @date:2026-07-13
 * @description:提供 Moqi 后端应用启动入口。
 */
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
