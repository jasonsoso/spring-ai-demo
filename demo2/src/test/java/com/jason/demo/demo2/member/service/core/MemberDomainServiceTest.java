package com.jason.demo.demo2.member.service.core;

import com.jason.demo.demo2.member.service.core.domain.Member;
import com.jason.demo.demo2.member.service.infrastructure.repository.MemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MemberDomainServiceTest {

    @Test
    void registerConvertsDuplicateInsertToConflict() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        Member member = new Member();
        member.setPhone("13888999999");
        when(memberRepository.findByPhone(member.getPhone())).thenReturn(Optional.empty());
        doThrow(new DuplicateKeyException("duplicate phone"))
                .when(memberRepository).insert(member);
        MemberDomainService service = new MemberDomainService(memberRepository);

        MemberDomainException exception = assertThrows(
                MemberDomainException.class,
                () -> service.register(member));

        assertEquals(MemberDomainException.Code.CONFLICT, exception.getCode());
        assertEquals("phone already registered", exception.getMessage());
    }

    @Test
    void registerDoesNotConvertOtherDataIntegrityViolations() {
        MemberRepository memberRepository = mock(MemberRepository.class);
        Member member = new Member();
        member.setPhone("13888999999");
        DataIntegrityViolationException originalException =
                new DataIntegrityViolationException("phone too long");
        when(memberRepository.findByPhone(member.getPhone())).thenReturn(Optional.empty());
        doThrow(originalException).when(memberRepository).insert(member);
        MemberDomainService service = new MemberDomainService(memberRepository);

        DataIntegrityViolationException thrownException = assertThrows(
                DataIntegrityViolationException.class,
                () -> service.register(member));

        assertSame(originalException, thrownException);
    }
}
