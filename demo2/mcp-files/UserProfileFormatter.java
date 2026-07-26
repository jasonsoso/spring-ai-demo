package com.example.profile;

import java.util.Map;

public class UserProfileFormatter {

    public String displayName(Map<String, Object> user) {
        System.out.println("Formatting user profile: " + user);
        return user.get("name").toString().strip();
    }
}
