package com.shop.api.domain.order.dto.response;

import com.shop.api.domain.order.entity.Order;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
public class OrderResponse {

    private Long id;
    private Long userId;
    private Long productId;
    private String productName;
    private int quantity;
    private int totalPrice;
    private String status;
    private LocalDateTime createdAt;

    public static OrderResponse from(Order order) {
        OrderResponse response = new OrderResponse();
        response.id = order.getId();
        response.userId = order.getUser().getId();
        response.productId = order.getProduct().getId();
        response.productName = order.getProduct().getName();
        response.quantity = order.getQuantity();
        response.totalPrice = order.getTotalPrice();
        response.status = order.getStatus().name();
        response.createdAt = order.getCreatedAt();
        return response;
    }
}