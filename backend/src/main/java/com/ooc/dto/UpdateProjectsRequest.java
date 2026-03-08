package com.ooc.dto;

import lombok.Data;
import java.util.List;

@Data
public class UpdateProjectsRequest {
    private List<String> projects;
}
