package dev.repair.api.common;

import java.util.List;

public record PageResult<T>(List<T> items, int page, int size, long total) {
}
