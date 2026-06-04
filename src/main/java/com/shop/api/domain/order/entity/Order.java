package com.shop.api.domain.order.entity;

import com.shop.api.domain.product.entity.Product;
import com.shop.api.domain.user.entity.User;
import com.shop.api.global.entity.BaseEntity;
import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "orders")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class Order extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false)
    private int totalPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private OrderStatus status;

    public enum OrderStatus {
        PENDING, PAID, CANCELLED
    }

    public static Order create(User user, Product product, int quantity) {
        Order order = new Order();
        order.user = user;
        order.product = product;
        order.quantity = quantity;
        order.totalPrice = product.getPrice() * quantity;
        order.status = OrderStatus.PENDING;
        return order;
    }

    public void cancel() {
        if (this.status == OrderStatus.CANCELLED) {
            throw new IllegalArgumentException("이미 취소된 주문입니다.");
        }
        this.status = OrderStatus.CANCELLED;
    }
}