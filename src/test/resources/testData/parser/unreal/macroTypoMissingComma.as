// The engine compiles this, but reads 'Animation" meta = (ShowOnlyInnerProperties)' as the
// category and loses the meta, so it stays an error here
class AExample
{
    UPROPERTY(BlueprintReadOnly, Category = "Animation" meta = (ShowOnlyInnerProperties))
    FLocomotionFeatureOpportunityAttackAnimData AnimData;
}
