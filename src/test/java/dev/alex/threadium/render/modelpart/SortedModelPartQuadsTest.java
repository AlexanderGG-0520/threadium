package dev.alex.threadium.render.modelpart;

import org.joml.Matrix4f;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SortedModelPartQuadsTest {
    private static ModelPartBoneData bone(float tx,float ty,float tz){
        float[] m=new float[28];m[0]=m[5]=m[10]=m[15]=1;m[12]=tx;m[13]=ty;m[14]=tz;
        m[16]=m[20]=m[24]=1;
        return new ModelPartBoneData(m,new long[]{1});
    }

    @Test void perspectiveOrderIsFarToNearAndStableForTies(){
        var refs=new ArrayList<>(List.of(
                new SortedModelPartQuads.Reference(0,0,4,0),
                new SortedModelPartQuads.Reference(1,0,9,1),
                new SortedModelPartQuads.Reference(2,0,9,2),
                new SortedModelPartQuads.Reference(3,0,1,3)));
        SortedModelPartQuads.sort(refs);
        assertEquals(List.of(1,2,0,3),refs.stream().map(SortedModelPartQuads.Reference::instanceIndex).toList());
    }

    @Test void keyUsesBoneThenRootTransform(){
        var boneMoved=SortedModelPartQuads.reference(0,0,0,1,0,0,0,bone(2,0,0),new Matrix4f());
        var rootMoved=SortedModelPartQuads.reference(0,0,0,1,0,0,0,bone(0,0,0),new Matrix4f().translate(2,0,0));
        assertEquals(9,boneMoved.distanceSquared());
        assertEquals(9,rootMoved.distanceSquared());
    }

    @Test void differentInstancesCanProduceDifferentQuadOrder(){
        var refs=new ArrayList<SortedModelPartQuads.Reference>();
        refs.add(SortedModelPartQuads.reference(0,0,0,1,0,0,0,bone(0,0,0),new Matrix4f()));
        refs.add(SortedModelPartQuads.reference(1,0,1,1,0,0,0,bone(0,0,0),new Matrix4f().translate(10,0,0)));
        SortedModelPartQuads.sort(refs);
        assertEquals(1,refs.getFirst().instanceIndex());
    }

    @Test void auditedSortOnUploadDescriptorsUseSortedPolicy(){
        var expected=java.util.Set.of(ModelPartPipelineDescriptor.ARMOR_TRANSLUCENT,
                ModelPartPipelineDescriptor.ENTITY_TRANSLUCENT,ModelPartPipelineDescriptor.ENTITY_TRANSLUCENT_CULL,
                ModelPartPipelineDescriptor.ENTITY_TRANSLUCENT_EMISSIVE,ModelPartPipelineDescriptor.BANNER_PATTERN,
                ModelPartPipelineDescriptor.BREEZE_WIND,ModelPartPipelineDescriptor.ENERGY_SWIRL,
                ModelPartPipelineDescriptor.EYES,ModelPartPipelineDescriptor.CRUMBLING);
        for(var descriptor:ModelPartPipelineDescriptor.values())
            assertEquals(expected.contains(descriptor),descriptor.submissionPolicy()==ModelPartPipelineDescriptor.SubmissionPolicy.SORTED_QUAD_STREAM,descriptor.name());
    }
}
