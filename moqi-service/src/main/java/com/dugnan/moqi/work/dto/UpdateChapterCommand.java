package com.dugnan.moqi.work.dto;

/**
 * @author dgn
 * @date 2026-08-07
 * @description 承载章节标题修改所需的客户端版本和新标题。
 */
public record UpdateChapterCommand(String title, Integer baseVersion) {
}
