package com.aifuli.module.supplychain;

import com.aifuli.module.supplychain.infrastructure.outbox.OutboxAppendIsolationConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(OutboxAppendIsolationConfiguration.class)
public class SupplyChainServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(SupplyChainServerApplication.class, args);
    }

}
