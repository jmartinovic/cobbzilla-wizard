package org.cobbzilla.wizard.client;

import lombok.Getter;
import org.cobbzilla.util.json.JsonUtil;
import org.cobbzilla.wizard.util.RestResponse;
import org.cobbzilla.wizard.validation.ConstraintViolationBean;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public class ValidationException extends ApiException {

    @Getter private Map<String, ConstraintViolationBean> violations;

    public ValidationException (RestResponse response) {
        super(response);
        this.violations = mapViolations(response.json);
    }

    protected Map<String, ConstraintViolationBean> mapViolations(String json) {
        Map<String, ConstraintViolationBean> map = new HashMap<>();
        ConstraintViolationBean[] violations = new ConstraintViolationBean[0];
        try {
            violations = JsonUtil.FULL_MAPPER.readValue(json, ConstraintViolationBean[].class);
        } catch (IOException e) {
            throw new IllegalArgumentException("Error parsing as ConstraintViolationBean[]: "+json+": "+e);
        }
        for (ConstraintViolationBean violation : violations) {
            map.put(violation.getMessageTemplate(), violation);
        }
        return map;
    }
}
