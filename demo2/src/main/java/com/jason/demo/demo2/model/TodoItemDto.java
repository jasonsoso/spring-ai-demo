package com.jason.demo.demo2.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TodoItemDto {

    private String content;
    private String status;
    private String activeForm;
}
