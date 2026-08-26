package com.jason.demo.demo2.product.app.vo.res;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
public class ProductDetailResVO extends ProductListItemResVO {
    private String detailContent;
}
