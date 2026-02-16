package com.ooc.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class UpdateUserRequest {
    
    @Size(max = 50, message = "Nickname must be at most 50 characters")
    private String nickname;
    
    @Email(message = "Invalid email format")
    private String email;
    
    private String avatar;
    
    // 可选：修改密码
    private String currentPassword;
    
    @Size(min = 6, message = "New password must be at least 6 characters")
    private String newPassword;
}
