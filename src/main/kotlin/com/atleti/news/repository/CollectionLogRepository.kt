package com.atleti.news.repository

import com.atleti.news.entity.CollectionLog
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.stereotype.Repository

@Repository
interface CollectionLogRepository : JpaRepository<CollectionLog, Long>
