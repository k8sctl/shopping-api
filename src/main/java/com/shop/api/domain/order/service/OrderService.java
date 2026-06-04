package com.shop.api.domain.order.service;

import com.shop.api.domain.order.dto.request.OrderCreateRequest;
import com.shop.api.domain.order.dto.response.OrderResponse;
import com.shop.api.domain.order.entity.Order;
import com.shop.api.domain.order.repository.OrderRepository;
import com.shop.api.domain.product.entity.Product;
import com.shop.api.domain.product.repository.ProductRepository;
import com.shop.api.domain.user.entity.User;
import com.shop.api.domain.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderRepository orderRepository;
    private final ProductRepository productRepository;
    private final UserRepository userRepository;

    @Transactional
    public OrderResponse create(String email, OrderCreateRequest request) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));

        Product product = productRepository.findById(request.getProductId())
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));

        product.decreaseStock(request.getQuantity());

        Order order = Order.create(user, product, request.getQuantity());
        return OrderResponse.from(orderRepository.save(order));
    }

    public List<OrderResponse> getMyOrders(String email) {
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다."));
        return orderRepository.findByUserId(user.getId())
                .stream()
                .map(OrderResponse::from)
                .toList();
    }

    public OrderResponse getOne(Long orderId, String email) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        if (!order.getUser().getEmail().equals(email)) {
            throw new IllegalArgumentException("본인의 주문만 조회할 수 있습니다.");
        }

        return OrderResponse.from(order);
    }

    @Transactional
    public void cancel(Long orderId, String email) {
        Order order = orderRepository.findById(orderId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        if (!order.getUser().getEmail().equals(email)) {
            throw new IllegalArgumentException("본인의 주문만 취소할 수 있습니다.");
        }

        order.cancel();
    }
}