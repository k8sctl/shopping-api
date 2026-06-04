package com.shop.api.domain.user.dto.response;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public class LoginResponse {
    private String accessToken;
    private String tokenType = "Bearer";

    public static LoginResponse of(String accessToken) {
        return new LoginResponse(accessToken, "Bearer");
    }
}