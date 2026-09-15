package com.mealmind.mapper;

import com.mealmind.entity.SessionRow;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface SessionMapper {

    int insert(SessionRow row);

    SessionRow findById(@Param("id") String id, @Param("userId") Long userId);

    int update(SessionRow row);
}
