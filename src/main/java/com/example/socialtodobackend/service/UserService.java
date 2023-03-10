package com.example.socialtodobackend.service;

import com.example.socialtodobackend.dto.publictodo.PublicTodoDto;
import com.example.socialtodobackend.dto.user.UserDto;
import com.example.socialtodobackend.dto.user.UserSignInRequestDto;
import com.example.socialtodobackend.dto.user.UserSignInResponseDto;
import com.example.socialtodobackend.dto.user.UserSignUpRequestDto;
import com.example.socialtodobackend.exception.SingletonException;
import com.example.socialtodobackend.persist.FollowEntity;
import com.example.socialtodobackend.persist.FollowRepository;
import com.example.socialtodobackend.persist.PublicTodoRepository;
import com.example.socialtodobackend.persist.UserEntity;
import com.example.socialtodobackend.persist.UserRepository;
import com.example.socialtodobackend.persist.redis.FolloweeListCacheRepository;
import com.example.socialtodobackend.persist.redis.JwtCacheRepository;
import com.example.socialtodobackend.persist.redis.numbers.NagNumberCacheRepository;
import com.example.socialtodobackend.persist.redis.numbers.SupportNumberCacheRepository;
import com.example.socialtodobackend.security.JWTProvider;
import com.example.socialtodobackend.utils.CommonUtils;
import java.time.LocalDate;
import java.util.List;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserService {

    private final UserRepository userRepository;
    private final FollowRepository followRepository;
    private final PublicTodoRepository publicTodoRepository;
    private final JWTProvider jwtProvider;
    private final PasswordEncoder passwordEncoder;
    private final JwtCacheRepository jwtCacheRepository;
    private final FolloweeListCacheRepository followeeListCacheRepository;
    private final SupportNumberCacheRepository supportNumberCacheRepository;
    private final NagNumberCacheRepository nagNumberCacheRepository;



    /**
     * ?????? ????????? ??????????????? ????????????.
     * JWT??? ?????? ???????????? ?????? ?????????, ???????????? ???????????? ?????? ???????????? ??????.
     * <br><br/>
     * ???????????? ?????????(??????????????? ???????????? ??????)??? ????????? ??????, ????????? ????????? ?????? ?????? ????????? ????????????.
     * */
    @Transactional
    public UserDto registerUser(UserSignUpRequestDto userSignUpRequestDto) {

        validateNicknameAndEmailAddress(userSignUpRequestDto.getNickname(), userSignUpRequestDto.getEmailAddr());

        UserEntity userEntity = userRepository.save(
            UserEntity.builder()
                .emailAddr(userSignUpRequestDto.getEmailAddr())
                .password(passwordEncoder.encode(userSignUpRequestDto.getPassword()))
                .nickname(userSignUpRequestDto.getNickname())
                .build()
        );
        return UserDto.fromEntity(userEntity);
    }



    @Transactional(readOnly = true)
    public UserSignInResponseDto authenticateUser(UserSignInRequestDto signInRequestDto) {
        UserEntity originalUserEntity = getByCredentials(
            signInRequestDto.getEmail(),
            signInRequestDto.getPassword(),
            passwordEncoder
        );

        //getByCredentials() ???????????? ???????????????, ?????? ???????????? ???????????? ????????? ?????? ????????? ?????? ??? ??? ??????.
        String jwt = jwtProvider.create(originalUserEntity);

        Long userPKId = jwtProvider.validateAndGetUserPKId(jwt);

        //????????? JWT??? ???????????? ????????? ??????.
        jwtCacheRepository.setJwtAtRedis(jwt, userPKId);

        return UserSignInResponseDto.fromEntity(originalUserEntity, jwt);
    }



    /**
     * ?????? ????????? ???????????? ????????? ????????????.
     * <br><br/>
     * ???????????? ????????? ???????????? ??????????????? ????????? ??????.
     * "?????? ????????? ???????????? ?????? ?????? ???????????? ?????? ?????? ?????? ?????? ???????????? ????????? ??????????????? ????????? ??????????????? ?????? ???????????? ?????? ???????????? ????????????"
     * ??? ???????????? ???????????? ??????.
     * <br><br/>
     * ????????? ?????? ????????? ?????? DB??? ??????.
     * */
    @Transactional(readOnly = true)
    public List<PublicTodoDto> makeTimeLine(Long userPKId, PageRequest pageRequest) {
        List<Long> followeeUserPKIdList;
        if(followeeListCacheRepository.isFolloweeListCacheHit(userPKId)){
            //?????? ??????
            followeeUserPKIdList = followeeListCacheRepository.getFolloweeList(userPKId);
        } else {
            //?????? ??????
            // userPKId ????????? ????????? ????????? ?????? ????????? ???????????? ??? ?????? ?????? ???????????? ?????? ????????? ????????? ????????????.
            // ??? ????????? 5000??? ????????? ??? ????????????, ?????? ?????? ?????? ??????.
            //???????????? ???????????? ?????? ????????? ???????????? ????????????.
            followeeUserPKIdList = followRepository.findAllByFollowSentUserId(userPKId).stream().map(FollowEntity::getFollowReceivedUserId).collect(Collectors.toList());

            //??? ???, ?????? ????????? ????????? ????????? ????????? ????????????.
            followeeListCacheRepository.setFolloweeList(followeeUserPKIdList, userPKId);
        }

        // ????????? ????????? followeeUserPKIdList ???
        // findAllByFinishedEqualsAndDeadlineDateEqualsAndAuthorUserIdIn() ???????????? ????????????
        // ????????? ??????????????????, ?????? ???????????? ?????? ?????? ?????? ????????? ?????????
        // ????????? ?????? ???????????? followeeUserPKIdList ??? ?????? ?????? ?????? ?????? ???????????? publicTodoDto??? ?????????, ??? ???????????? ?????? ??? ????????? ?????? ?????????
        // ?????? ????????????.
        // ????????? ????????? ????????? ???, ??????????????? ????????? ??? ?????? ?????? ??????????????? ??????/????????? ????????? ???????????? dto??? ???????????? ??????.
        return publicTodoRepository.findAllByFinishedIsFalseAndDeadlineDateEqualsAndAuthorUserIdIn(
            LocalDate.now(),
            followeeUserPKIdList,
            pageRequest
        ).getContent().stream().map(
            publicTodoEntity -> PublicTodoDto.fromEntity(
                publicTodoEntity,
                supportNumberCacheRepository.getSupportNumber(publicTodoEntity.getId()),
                nagNumberCacheRepository.getNagNumber(publicTodoEntity.getId())
            )
        ).collect(Collectors.toList());
    }



    /**
     * ????????? ???????????? ????????????.
     * ???????????? ???????????? ?????? ???????????? ????????? ???????????? ?????? ?????? ???????????? ???????????? ????????? ?????????.
     * ???????????? ????????????.
     * */
    @Transactional(readOnly = true)
    public List<UserDto> searchUsersByNickname(String userNickname, PageRequest pageRequest) {
        validateNicknameString(userNickname);

        return userRepository.findAllByNicknameContaining(userNickname, pageRequest).getContent().stream().map(UserDto::fromEntity).collect(Collectors.toList());
    }



    /**
     * ????????? ?????? ???????????? ????????????.
     * */
    @Transactional
    public void updateUserStatusMessage(Long userPKId, String statusMessage) {
        //????????? ??? ????????? ???????????? ???????????? ????????? ??? ????????? ????????? ?????? ??????.
        UserEntity userEntity = userRepository.findById(userPKId).orElseThrow(
            () -> SingletonException.USER_NOT_FOUND
        );

        if(statusMessage.length() > CommonUtils.STATUS_MESSAGE_LENGTH_LIMIT){
            throw SingletonException.STATUS_MESSAGE_TOO_LONG;
        }

        userEntity.setStatusMessage(statusMessage);
        userRepository.save(userEntity);
    }



    //--------------- PRIVATE HELPER METHOD AREA ------------


    /**
     * ???????????? ???????????? ?????? ????????? ???????????? ???????????? ?????????,
     * ????????? ???????????????,
     * ????????? ????????? ??????????????? ???????????? ????????? ????????????.
     * */
    private void validateNicknameAndEmailAddress(String nickName, String emailAddr) {
        validateNicknameString(nickName);
        if(userRepository.existsByNickname(nickName)){
            throw SingletonException.NICKNAME_ALREADY_EXISTS;
        }
        if(userRepository.existsByEmailAddr(emailAddr)){
            throw SingletonException.EMAIL_ADDRESS_ALREADY_EXISTS;
        }
    }




    /**
     * ??????????????? ????????? ????????? ?????? ????????? ???????????? ?????? ????????? ????????? ?????????.
     * */
    private void validateNicknameString(String input){
        if(input==null || input.equals("")){
            throw SingletonException.INVALID_NICKNAME;
        }
        for(char c : input.toCharArray()){
            if(Character.isDigit(c) || Character.isLowerCase(c)){
                continue;
            }
            else{
                throw SingletonException.INVALID_NICKNAME;
            }
        }
    }



    /**
     * ????????? ????????? ??? ????????? ???????????? ????????? ?????? ????????? ???????????? ?????? ???????????? ????????????.
     * ??? ?????? ????????? ??????????????? Jwt??? ?????? ?????? ??????(==????????? ?????? ??????)??? ??? 1?????? ????????????.
     * ??? ????????? jwt??? ????????? ?????? ?????? jwt??? ???????????? ?????? ?????? ?????? ???????????? ?????????.
     * */
    private UserEntity getByCredentials(
        String userEmail, String password, PasswordEncoder passwordEncoder
    ){
        UserEntity originalUserEntity = userRepository.findByEmailAddr(userEmail).orElseThrow(
            () -> SingletonException.USER_NOT_FOUND
        );

        if(passwordEncoder.matches(password, originalUserEntity.getPassword())){
            return originalUserEntity;
        }
        else{
            throw SingletonException.PASSWORD_NOT_MATCH;
        }
    }

}
