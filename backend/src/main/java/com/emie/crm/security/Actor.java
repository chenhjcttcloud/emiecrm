package com.emie.crm.security;

import com.emie.crm.common.exception.ApiException;
import java.util.Set;

public record Actor(String name, String role, Set<String> permissions) {
  public void require(String permission) {
    if (!permissions.contains(permission)) throw new ApiException(403, "无操作权限：" + permission);
  }
}
