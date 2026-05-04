/**
@license

   Copyright (C) 1997 - 2002, Makoto Matsumoto and Takuji Nishimura,
   All rights reserved.

   Redistribution and use in source and binary forms, with or without
   modification, are permitted provided that the following conditions
   are met:

     1. Redistributions of source code must retain the above copyright
        notice, this list of conditions and the following disclaimer.

     2. Redistributions in binary form must reproduce the above copyright
        notice, this list of conditions and the following disclaimer in the
        documentation and/or other materials provided with the distribution.

     3. The names of its contributors may not be used to endorse or promote
        products derived from this software without specific prior written
        permission.

   THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
   "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
   LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
   A PARTICULAR PURPOSE ARE DISCLAIMED.  IN NO EVENT SHALL THE COPYRIGHT OWNER OR
   CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL,
   EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT LIMITED TO,
   PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE, DATA, OR
   PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY THEORY OF
   LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT (INCLUDING
   NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
   SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
*/
var _0x52478d=_0x291d;function _0x291d(_0x23caba,_0x16faf5){var _0x5429a3=_0x5429();return _0x291d=function(_0x291de8,_0x27f8b3){_0x291de8=_0x291de8-0xcf;var _0x49532c=_0x5429a3[_0x291de8];return _0x49532c;},_0x291d(_0x23caba,_0x16faf5);}(function(_0x4a000a,_0x35a164){var _0x42d762=_0x291d,_0x1b7d5a=_0x4a000a();while(!![]){try{var _0x3b1045=-parseInt(_0x42d762(0xd2))/0x1+-parseInt(_0x42d762(0xe0))/0x2+-parseInt(_0x42d762(0xd8))/0x3*(parseInt(_0x42d762(0xd9))/0x4)+parseInt(_0x42d762(0xdb))/0x5+-parseInt(_0x42d762(0xd3))/0x6+parseInt(_0x42d762(0xd7))/0x7*(parseInt(_0x42d762(0xde))/0x8)+parseInt(_0x42d762(0xd6))/0x9*(parseInt(_0x42d762(0xd0))/0xa);if(_0x3b1045===_0x35a164)break;else _0x1b7d5a['push'](_0x1b7d5a['shift']());}catch(_0x5d103e){_0x1b7d5a['push'](_0x1b7d5a['shift']());}}}(_0x5429,0x7b5ee));function _0x5429(){var _0x57ed76=['UPPER_MASK','4903605IPjHFc','mti','LOWER_MASK','8aVVMrp','prototype','1622044SutZka','MATRIX_A','6068110XbFAeR','random','913366yfaXii','5748102SyygmL','init_genrand','genrand_int32','45uFIEuZ','217378PhbBpu','858099tLXjUW','12WrbcoO'];_0x5429=function(){return _0x57ed76;};return _0x5429();}var MersenneTwister=function(_0x4dbc27){var _0x5c7e31=_0x291d;_0x4dbc27==undefined&&(_0x4dbc27=new Date()['getTime']()),this['N']=0x270,this['M']=0x18d,this[_0x5c7e31(0xcf)]=0x9908b0df,this[_0x5c7e31(0xda)]=0x80000000,this[_0x5c7e31(0xdd)]=0x7fffffff,this['mt']=new Array(this['N']),this[_0x5c7e31(0xdc)]=this['N']+0x1,this[_0x5c7e31(0xd4)](_0x4dbc27);};MersenneTwister['prototype'][_0x52478d(0xd4)]=function(_0x205c5b){var _0x21ef42=_0x52478d;this['mt'][0x0]=_0x205c5b>>>0x0;for(this[_0x21ef42(0xdc)]=0x1;this[_0x21ef42(0xdc)]<this['N'];this[_0x21ef42(0xdc)]++){var _0x205c5b=this['mt'][this['mti']-0x1]^this['mt'][this[_0x21ef42(0xdc)]-0x1]>>>0x1e;this['mt'][this[_0x21ef42(0xdc)]]=(((_0x205c5b&0xffff0000)>>>0x10)*0x6c078965<<0x10)+(_0x205c5b&0xffff)*0x6c078965+this[_0x21ef42(0xdc)],this['mt'][this['mti']]>>>=0x0;}},MersenneTwister[_0x52478d(0xdf)][_0x52478d(0xd5)]=function(){var _0x150703=_0x52478d,_0x33b481,_0x34c091=new Array(0x0,this[_0x150703(0xcf)]);if(this[_0x150703(0xdc)]>=this['N']){var _0xe8e427;if(this[_0x150703(0xdc)]==this['N']+0x1)this[_0x150703(0xd4)](0x1571);for(_0xe8e427=0x0;_0xe8e427<this['N']-this['M'];_0xe8e427++){_0x33b481=this['mt'][_0xe8e427]&this[_0x150703(0xda)]|this['mt'][_0xe8e427+0x1]&this[_0x150703(0xdd)],this['mt'][_0xe8e427]=this['mt'][_0xe8e427+this['M']]^_0x33b481>>>0x1^_0x34c091[_0x33b481&0x1];}for(;_0xe8e427<this['N']-0x1;_0xe8e427++){_0x33b481=this['mt'][_0xe8e427]&this['UPPER_MASK']|this['mt'][_0xe8e427+0x1]&this[_0x150703(0xdd)],this['mt'][_0xe8e427]=this['mt'][_0xe8e427+(this['M']-this['N'])]^_0x33b481>>>0x1^_0x34c091[_0x33b481&0x1];}_0x33b481=this['mt'][this['N']-0x1]&this['UPPER_MASK']|this['mt'][0x0]&this[_0x150703(0xdd)],this['mt'][this['N']-0x1]=this['mt'][this['M']-0x1]^_0x33b481>>>0x1^_0x34c091[_0x33b481&0x1],this['mti']=0x0;}return _0x33b481=this['mt'][this[_0x150703(0xdc)]++],_0x33b481^=_0x33b481>>>0xb,_0x33b481^=_0x33b481<<0x7&0x9d2c5680,_0x33b481^=_0x33b481<<0xf&0xefc60000,_0x33b481^=_0x33b481>>>0x12,_0x33b481>>>0x0;},MersenneTwister[_0x52478d(0xdf)][_0x52478d(0xd1)]=function(){var _0x5995b7=_0x52478d;return this[_0x5995b7(0xd5)]()*(0x1/0x100000000);};