package com.investmentresearch.data.persistence;

import java.util.List;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface MessageMapper {
    /** Allocates the next seq in the same statement, so concurrent appends cannot collide. */
    @Select(
            "INSERT INTO message (conversation_id, seq, role, payload_json, created_at)"
                    + " SELECT #{conversationId}, COALESCE(MAX(seq), 0) + 1, #{role},"
                    + " #{payloadJson}, #{createdAt} FROM message"
                    + " WHERE conversation_id = #{conversationId} RETURNING seq")
    int insertNextSeq(MessageEntity row);

    @Select(
            "SELECT role, payload_json AS payloadJson FROM message"
                    + " WHERE conversation_id = #{conversationId} ORDER BY seq")
    List<MessageEntity> listByConversation(@Param("conversationId") long conversationId);

    @Delete("DELETE FROM message WHERE conversation_id = #{conversationId}")
    int deleteByConversation(@Param("conversationId") long conversationId);
}
