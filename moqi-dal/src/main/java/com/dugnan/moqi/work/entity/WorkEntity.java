package com.dugnan.moqi.work.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Getter;
import lombok.Setter;

import com.dugnan.moqi.common.entity.BaseEntity;

@Getter
@Setter
@TableName("works")
public class WorkEntity extends BaseEntity {

    private String title;

    private String status;
}
