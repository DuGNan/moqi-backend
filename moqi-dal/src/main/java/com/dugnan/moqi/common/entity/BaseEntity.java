/**
 * @author dgn
 * @date:2026-07-13
 * @description:定义持久化实体通用的主键、审计时间和软删除字段。
 */
package com.dugnan.moqi.common.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
public class BaseEntity {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Integer deleted;

    private Integer version;

    @TableField("gmt_create")
    private LocalDateTime gmtCreate;

    @TableField("gmt_modified")
    private LocalDateTime gmtModified;
}
