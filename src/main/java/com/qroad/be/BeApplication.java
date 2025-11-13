package com.qroad.be;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class BeApplication {

	public static void main(String[] args) {
        System.out.println("Hello world");
		SpringApplication.run(BeApplication.class, args);
	}

}
