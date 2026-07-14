package com.jason.demo.demo2.embabel.service;

import com.jason.demo.demo2.embabel.agent.PolicyAgent;
import org.springframework.stereotype.Service;

@Service
public class PolicyKnowledgeService {

    public PolicyAgent.PolicyMaterial findPolicy(String category, String question) {
        String normalized = ((category == null ? "" : category) + " " + (question == null ? "" : question));
        if (normalized.contains("请假")) {
            return new PolicyAgent.PolicyMaterial("员工请假制度",
                    "年假、病假、事假都需要在系统里提交申请。病假需要补充医院证明或就诊记录。"
                            + "连续请假超过 3 天，需要直属负责人和部门负责人审批。紧急情况可以先口头同步，回到岗位后补提申请。");
        }
        if (normalized.contains("报销") || normalized.contains("差旅") || normalized.contains("出差")) {
            return new PolicyAgent.PolicyMaterial("差旅与报销制度",
                    "出差回来后，需要提交出差审批单、交通票据、住宿发票、行程说明和费用明细。"
                            + "住宿、交通和餐补按公司差旅标准核销。报销应在返程后 7 个工作日内提交。"
                            + "如果票据缺失，需要补充情况说明并由直属负责人确认。");
        }
        return new PolicyAgent.PolicyMaterial("通用制度说明",
                "制度问题需要先确认所属类别、适用人员范围和生效时间。资料不明确时，应提示员工联系人事或财务确认。");
    }
}
