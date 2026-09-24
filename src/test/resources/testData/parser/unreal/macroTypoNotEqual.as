// The engine compiles this, but reads 'EditCondition !' as the meta key, so the condition does
// nothing; it stays an error here
class AExample
{
    UPROPERTY(Meta = (EditCondition != bUseCustomMovement, EditConditionHides))
    const float MoveSpeed = 400;
}
