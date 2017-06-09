package com.ecreditpal.maas.service.model.handler;

import lombok.Getter;
import lombok.Setter;

import java.util.Map;

/**
 * @author lifeng
 * @version  2017/4/14.
 */
@Getter
@Setter
public class HttpParam extends RequestParam {

    private String url;

    public HttpParam(String url,Map<String,Object> param) {
        this.url = url;
        this.param = param;
    }
}