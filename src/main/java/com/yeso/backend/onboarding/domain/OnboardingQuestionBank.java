package com.yeso.backend.onboarding.domain;

import java.util.List;
import java.util.Map;

import static com.yeso.backend.onboarding.domain.OnboardingAxis.EI;
import static com.yeso.backend.onboarding.domain.OnboardingAxis.JP;
import static com.yeso.backend.onboarding.domain.OnboardingAxis.SN;
import static com.yeso.backend.onboarding.domain.OnboardingAxis.TF;

/**
 * `docs/mvp/onboarding-questionnaire.md`의 질문·글자 매핑·태그 사전을 그대로 옮긴 서버 상수다.
 * 문구나 매핑을 바꾸면 이 클래스를 고치지 말고 새 {@code questionVersion}을 만든다.
 */
public final class OnboardingQuestionBank {

    public static final String QUESTION_VERSION = "demo-mbti-v1";

    public static final List<OnboardingQuestion> QUESTIONS = List.of(
            new OnboardingQuestion(1, JP, "여행을 떠날 때 계획은",
                    "내가 걷는 길이 곧 여행코스", 'P', "계획은 필수", 'J'),
            new OnboardingQuestion(2, JP, "여행 경비는",
                    "당장 국제거지만 안되면 되지!", 'P', "걸어다니는 계산기로 변신", 'J'),
            new OnboardingQuestion(3, JP, "여행을 다녀온 후",
                    "홈스윗홈.. 침대로 점프!", 'P', "캐리어를 열고 물건을 정리한다", 'J'),
            new OnboardingQuestion(4, JP, "여행지에서 식사할 때",
                    "유~명한 맛집을 작정하고 노리는 헌터", 'J', "처음 본 순간 사랑에 빠진 길거리 가게", 'P'),
            new OnboardingQuestion(5, SN, "여행지에서 길을 잃었을 때",
                    "왔던 길로 돌아가는 헨젤과 그레텔st.", 'S', "자꾸 걸어 나가면 길이 있겠지, 지구는 둥그니까", 'N'),
            new OnboardingQuestion(6, SN, "화려한 건축물을 보며 드는 생각은",
                    "\"어떤 방법으로 지었을까?\" 고민한다", 'S', "\"와 멋있다...\" 감탄한다", 'N'),
            new OnboardingQuestion(7, TF, "아침에 늦잠 잔 친구에게",
                    "\"여행이 역시 피곤하지.\"", 'F', "\"내일은 시간 지키자.\"", 'T'),
            new OnboardingQuestion(8, TF, "친구에게 차 사고가 났다고 전화 왔을 때 나의 대답은",
                    "\"괜찮아? ㅠㅠ 다친 데는 없어?\"", 'F', "\"보험 들었어?\"", 'T'),
            new OnboardingQuestion(9, TF, "친구가 쓸데없는 기념품을 살 때",
                    "\"그래 니가 행복하다면...\"", 'F', "\"그거 결국 쓰레기 된다\"", 'T'),
            new OnboardingQuestion(10, EI, "나는 여행지를 선택할 때 주로",
                    "사람이 많은 도시로", 'E', "나무가 많은 자연으로", 'I'),
            new OnboardingQuestion(11, EI, "숙소를 구할 때",
                    "저녁에 바비큐 파티를 여는 곳", 'E', "조용하고 아늑한 곳", 'I'),
            new OnboardingQuestion(12, EI, "여행지에 대한 감상을",
                    "말로 내뱉어야 직성이 풀린다", 'E', "내 마음 속에 저장, 마음에 담고 느낀다", 'I')
    );

    public static final Map<Integer, OnboardingQuestion> QUESTIONS_BY_NUMBER =
            QUESTIONS.stream().collect(java.util.stream.Collectors.toMap(OnboardingQuestion::number, q -> q));

    /** MBTI 최종 코드 조립 순서(EI+SN+TF+JP)와 동일하다. */
    public static final List<OnboardingAxis> AXIS_ORDER = List.of(EI, SN, TF, JP);

    public static final List<String> EXPERIENCE_TAGS = List.of(
            "자연", "바다", "산", "산책", "골목", "역사", "시장", "로컬 음식", "카페", "휴식", "실내", "체험");

    public static final int MAX_EXPERIENCE_TAGS = 5;

    public static final List<String> EXCLUDE_TAGS = List.of(
            "계단·경사 많은 곳", "물놀이", "야간 이동", "오래 걷기");

    public static final int MAX_LIKED_TRIPS = 30;

    private OnboardingQuestionBank() {
    }
}
