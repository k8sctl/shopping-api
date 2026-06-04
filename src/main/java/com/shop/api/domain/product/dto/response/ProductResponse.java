package com.shop.api.domain.product.dto.response;

import com.shop.api.domain.product.entity.Product;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class ProductResponse {

    private Long id;
    private String name;
    private String description;
    private int price;
    private int stock;
    private String status;
    private LocalDateTime createdAt;

    public static ProductResponse from(Product product) {
        ProductResponse response = new ProductResponse();
        response.id = product.getId();
        response.name = product.getName();
        response.description = product.getDescription();
        response.price = product.getPrice();
        response.stock = product.getStock();
        response.status = product.getStatus().name();
        response.createdAt = product.getCreatedAt();
        return response;
    }
}