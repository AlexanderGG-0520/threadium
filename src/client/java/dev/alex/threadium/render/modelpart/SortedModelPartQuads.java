package dev.alex.threadium.render.modelpart;

import org.joml.Matrix4fc;

import java.util.ArrayList;
import java.util.List;

/** Minecraft 26.2-compatible perspective ordering for transformed ModelPart quads. */
final class SortedModelPartQuads {
    private SortedModelPartQuads() {}

    record Reference(int instanceIndex, int quadIndex, float distanceSquared, long sequence) {}

    static Reference reference(int instanceIndex,int quadIndex,long sequence,float x,float y,float z,
                               int boneIndex,ModelPartBoneData bones,Matrix4fc root) {
        return referenceFromOppositeVertices(instanceIndex,quadIndex,sequence,x,y,z,x,y,z,boneIndex,bones,root);
    }

    static Reference referenceFromOppositeVertices(int instanceIndex,int quadIndex,long sequence,
                               float ax,float ay,float az,float cx,float cy,float cz,
                               int boneIndex,ModelPartBoneData bones,Matrix4fc root) {
        float[] first=transform(ax,ay,az,boneIndex,bones,root);
        float[] opposite=transform(cx,cy,cz,boneIndex,bones,root);
        float px=(first[0]+opposite[0])*0.5f;
        float py=(first[1]+opposite[1])*0.5f;
        float pz=(first[2]+opposite[2])*0.5f;
        return new Reference(instanceIndex,quadIndex,px*px+py*py+pz*pz,sequence);
    }

    private static float[] transform(float x,float y,float z,int boneIndex,ModelPartBoneData bones,Matrix4fc root) {
        int base=boneIndex*28;
        float[] m=bones.matrices();
        float bx=m[base]*x+m[base+4]*y+m[base+8]*z+m[base+12];
        float by=m[base+1]*x+m[base+5]*y+m[base+9]*z+m[base+13];
        float bz=m[base+2]*x+m[base+6]*y+m[base+10]*z+m[base+14];
        float px=root.m00()*bx+root.m10()*by+root.m20()*bz+root.m30();
        float py=root.m01()*bx+root.m11()*by+root.m21()*bz+root.m31();
        float pz=root.m02()*bx+root.m12()*by+root.m22()*bz+root.m32();
        return new float[]{px,py,pz};
    }

    static void sort(List<Reference> references) {
        // List.sort is stable. This matches VertexSorting's descending float key
        // and IntArrays.mergeSort retention of original order for equal keys.
        references.sort((left,right)->Float.compare(right.distanceSquared,left.distanceSquared));
    }

    static List<Reference> copyAndSort(List<Reference> references) {
        ArrayList<Reference> result=new ArrayList<>(references);
        sort(result);
        return result;
    }
}
