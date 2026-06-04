package com.shop.api.domain.user.service;

import com.shop.api.domain.user.dto.request.LoginRequest;
import com.shop.api.domain.user.dto.request.RegisterRequest;
import com.shop.api.domain.user.dto.response.LoginResponse;
import com.shop.api.domain.user.entity.User;
import com.shop.api.domain.user.repository.UserRepository;
import com.shop.api.global.config.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtProvider jwtProvider;

    @Transactional
    public void register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new IllegalArgumentException("이미 사용 중인 이메일입니다.");
        }

        User user = User.create(
                request.getEmail(),
                passwordEncoder.encode(request.getPassword()),
                request.getName()
        );

        userRepository.save(user);
    }

    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByEmail(request.getEmail())
                .orElseThrow(() -> new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다."));

        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new IllegalArgumentException("이메일 또는 비밀번호가 올바르지 않습니다.");
        }

        String token = jwtProvider.generateToken(user.getEmail(), user.getRole().name());
        return LoginResponse.of(token);
    }
}