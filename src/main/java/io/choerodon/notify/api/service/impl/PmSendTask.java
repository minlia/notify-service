package io.choerodon.notify.api.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import freemarker.template.TemplateException;
import io.choerodon.asgard.schedule.annotation.JobParam;
import io.choerodon.asgard.schedule.annotation.JobTask;
import io.choerodon.core.exception.CommonException;
import io.choerodon.notify.api.pojo.MessageType;
import io.choerodon.notify.domain.SiteMsgRecord;
import io.choerodon.notify.domain.Template;
import io.choerodon.notify.infra.feign.UserFeignClient;
import io.choerodon.notify.infra.mapper.SiteMsgRecordMapper;
import io.choerodon.notify.infra.mapper.TemplateMapper;
import io.choerodon.notify.websocket.send.MessageSender;
import io.choerodon.notify.websocket.send.WebSocketSendPayload;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @author dengyouquan
 **/
@Component
public class PmSendTask {
    private static final String CHOERODON_MSG_SIT_MSG = "choerodon:msg:site-msg:";
    private final Logger logger = LoggerFactory.getLogger(PmSendTask.class);
    private static final String MSG_TYPE_PM = "site-msg";
    private final TemplateMapper templateMapper;
    private final TemplateRender templateRender;
    private final MessageSender messageSender;
    private final SiteMsgRecordMapper siteMsgRecordMapper;
    private final ObjectMapper objectMapper;
    private final UserFeignClient userFeignClient;

    public PmSendTask(TemplateMapper templateMapper, TemplateRender templateRender,
                      MessageSender messageSender, UserFeignClient userFeignClient,
                      SiteMsgRecordMapper siteMsgRecordMapper) {
        this.templateMapper = templateMapper;
        this.templateRender = templateRender;
        this.messageSender = messageSender;
        this.userFeignClient = userFeignClient;
        this.siteMsgRecordMapper = siteMsgRecordMapper;
        objectMapper = new ObjectMapper();
    }

    @JobTask(maxRetryCount = 1, code = "sendStationLetter", params = {
            @JobParam(name = "code", defaultValue = MSG_TYPE_PM, description = "发送站内信编码[使用默认值]"),
            @JobParam(name = "templateCode", defaultValue = "addFunction-preset", description = "站内信模板编码"),
            @JobParam(name = "variables", defaultValue = "{\"content\":\"定时任务\"}", description = "站内信模板内容的渲染参数")
    }, description = "发送站内信")
    public void sendStationLetter(Map<String, Object> map) {
        String code = Optional.ofNullable((String) map.get("code")).orElse(MSG_TYPE_PM);
        String templateCode = Optional.ofNullable((String) map.get("templateCode")).orElse("addFunction-preset");
        String mapJson = Optional.ofNullable((String) map.get("variables")).orElse("{\"content\":\"定时任务\"}");
        Map<String, Object> params = new HashMap<>(0);
        if (!StringUtils.isEmpty(mapJson)) {
            try {
                params = objectMapper.readValue(mapJson, Map.class);
            } catch (IOException e) {
                throw new CommonException("error.pmSendTask.paramsJsonNotValid", e);
            }
        }
        Template template = validatorCode(code, templateCode);
        long startTime = System.currentTimeMillis();
        logger.info("PmSendTask send pm started");
        String pmContent = renderPmTemplate(template, params);
        Arrays.stream(userFeignClient.getUserIds().getBody()).parallel().forEach(id -> {
            SiteMsgRecord record = new SiteMsgRecord(id, template.getPmTitle(), pmContent);
            //oracle 不支持 insertList
            siteMsgRecordMapper.insert(record);
            String key = CHOERODON_MSG_SIT_MSG + id;
            messageSender.sendByKey(key, new WebSocketSendPayload<>(MSG_TYPE_PM, key, siteMsgRecordMapper.selectCountOfUnRead(id)));
        });
        long endTime = System.currentTimeMillis();
        logger.info("PmSendTask send pm completed. speed time:{} millisecond", (endTime - startTime));
    }

    private String renderPmTemplate(Template template, Map<String, Object> params) {
        String pm = template.getPmContent();
        try {
            pm = templateRender.renderPmTemplate(template, params);
        } catch (IOException | TemplateException e) {
            throw new CommonException("error.templateRender.renderError", e);
        }
        return pm;
    }

    private Template validatorCode(String code, String templateCode) {
        if (!MSG_TYPE_PM.equals(code)) {
            throw new CommonException("error.pmSendTask.codeNotValid");
        }
        if (StringUtils.isEmpty(templateCode)) {
            throw new CommonException("error.pmSendTask.templateCodeEmpty");
        }
        Template query = new Template();
        query.setCode(templateCode);
        query.setMessageType(MessageType.PM.getValue());
        Template template = templateMapper.selectOne(query);
        if (template == null) {
            throw new CommonException("error.pmSendTask.templateCodeNotExist");
        }
        return template;
    }
}