package com.investmentresearch.data.persistence;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ConversationMapper extends BaseMapper<ConversationEntity> {
    @Select(
            "SELECT c.*, (SELECT COUNT(*) FROM message m WHERE m.conversation_id = c.id)"
                    + " AS messageCount FROM conversation c WHERE c.id = #{id}")
    ConversationEntity findRow(@Param("id") long id);

    @Select(
            """
            <script>
            SELECT c.*, (SELECT COUNT(*) FROM message m WHERE m.conversation_id = c.id) AS messageCount
            FROM conversation c
            <where>
              <if test="query != null and query != ''">
                (c.title LIKE '%' || #{query} || '%' OR c.primary_code LIKE '%' || #{query} || '%')
              </if>
            </where>
            ORDER BY c.updated_at DESC, c.id DESC
            LIMIT #{limit}
            </script>
            """)
    List<ConversationEntity> recent(@Param("query") String query, @Param("limit") int limit);
}
