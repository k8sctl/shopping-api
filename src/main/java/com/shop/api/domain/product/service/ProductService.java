package com.shop.api.domain.product.service;

import com.shop.api.domain.product.dto.request.ProductCreateRequest;
import com.shop.api.domain.product.dto.request.ProductUpdateRequest;
import com.shop.api.domain.product.dto.response.ProductResponse;
import com.shop.api.domain.product.entity.Product;
import com.shop.api.domain.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProductService {

    private final ProductRepository productRepository;

    @Transactional
    public ProductResponse create(ProductCreateRequest request) {
        Product product = Product.create(
                request.getName(),
                request.getDescription(),
                request.getPrice(),
                request.getStock()
        );
        return ProductResponse.from(productRepository.save(product));
    }

    public List<ProductResponse> getAll() {
        return productRepository.findAll()
                .stream()
                .map(ProductResponse::from)
                .toList();
    }

    public ProductResponse getOne(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
        return ProductResponse.from(product);
    }

    @Transactional
    public ProductResponse update(Long id, ProductUpdateRequest request) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
        product.update(request.getName(), request.getDescription(), request.getPrice());
        return ProductResponse.from(product);
    }

    @Transactional
    public void delete(Long id) {
        Product product = productRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("상품을 찾을 수 없습니다."));
        productRepository.delete(product);
    }
}