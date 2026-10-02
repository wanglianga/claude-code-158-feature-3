package com.wasteoil.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 3 文档配置。Swagger UI 中通过右上角 Authorize 填入演示鉴权头：
 * X-Role（MERCHANT/RECYCLER/DRIVER/PLANT/REGULATOR）与 X-Account-Id。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI wasteOilOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("城市餐厨废油回收预约称重联单合规追踪服务 API")
                        .version("1.0.0")
                        .description("预约→派单→核验→称重→运输→到厂收料→合规闭环→结算全链路，"
                                + "以及监管链路追踪、现场检查任务与重点巡查。"))
                .components(new Components()
                        .addSecuritySchemes("roleHeader", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Role")
                                .description("角色：MERCHANT / RECYCLER / DRIVER / PLANT / REGULATOR"))
                        .addSecuritySchemes("accountHeader", new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-Account-Id")
                                .description("账号标识，如 regulator-1 / merchant-1 / driver-1")))
                .addSecurityItem(new SecurityRequirement()
                        .addList("roleHeader")
                        .addList("accountHeader"));
    }
}
