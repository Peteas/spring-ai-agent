package com.sakura.spring.ai.agent.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.sakura.spring.ai.agent.model.UserSession;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserSessionMapper extends BaseMapper<UserSession> {
}
